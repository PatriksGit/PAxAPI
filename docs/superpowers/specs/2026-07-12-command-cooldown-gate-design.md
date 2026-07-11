# Command per-key cooldown gate — design

Status: approved for planning
Date: 2026-07-12

## Motivation

Two downstream plugins (PAxAuth's `ReloadGate`, PAxStaffUtils's `HelpOpCooldown`) each hand-roll
the same per-sender cooldown logic on top of `CommandSpec`. This adds a first-class `.cooldown(...)`
gate to `CommandSpec.Builder` plus a standalone `CooldownTracker` class, so both call sites collapse
into the shared library.

This spec covers **only** the cooldown gate. Config write-back, Managed Executor, the soft-dependency
helper, and the PlaceholderAPI auto-hook are separate, independent specs.

## Governing principle

The cooldown is **not** a plain gate like `permission`/`requirement`/`playerOnly`. Those are idempotent
predicates — safe to evaluate any number of times, anywhere. `tryAcquire` has a side effect (it records
a use). That one fact drives every rule below:

1. **Only runs in `execute()`, never in `complete()`.** `requires()` predicates are already documented as
   evaluated during tab-completion (off-thread on Velocity). If cooldown reused that plumbing, merely
   tab-completing a command would consume it. `CommandDispatcher.canAccess()` (used by `complete()`) is
   left untouched — cooldown is wired only into `execute()`'s gate chain.
2. **Runs after routing resolves**, as the last gate — after `permission` → `requirement` → `playerOnly`
   all pass for the node. An unknown subcommand, an arg error, or a permission denial never consumes it.
3. **A blocked attempt has zero side effects.** The window always anchors to the last *permitted* use;
   spamming a blocked command never resets or extends it.

## 1. `SenderAdapter<S>` — new default method

```java
public interface SenderAdapter<S> {
    boolean hasPermission(S sender, String permission);
    boolean isPlayer(S sender);

    /**
     * Stable identity for cooldown-keying, or {@code null} if the sender has none
     * (console, command block, RCON). A null identity exempts the sender from cooldown
     * checks entirely — it is NOT bucketed together with other identity-less senders.
     */
    default String identity(S sender) { return null; }
}
```

- **`default`, not abstract** — does not break binary compatibility for existing `SenderAdapter`
  implementors. Consistent with the null semantics: an adapter that doesn't override it simply opts
  every sender out of cooldown gating (fail-open), rather than breaking the build.
- `PaperSenderAdapter.identity()`: `sender instanceof Player p ? p.getUniqueId().toString() : null`
- `VelocitySenderAdapter.identity()`: `sender instanceof Player p ? p.getUniqueId().toString() : null`
- `FakeSender` (test double) gets an explicit override so dispatcher tests can exercise cooldown behavior.

## 2. `CooldownTracker` — new class (`hu.patriksgit.paxapi.command`)

Plain `String`-keyed (not generic `<K>`) — every real key (`identity()`'s UUID string, or a fixed
constant for command-wide cooldowns) is already a `String`, so a type parameter would add nothing.

```java
public final class CooldownTracker {
    private final ConcurrentHashMap<String, Instant> lastUse = new ConcurrentHashMap<>();
    private final Clock clock;

    public CooldownTracker() { this(Clock.systemUTC()); }

    // package-private: lets tests inject a controllable clock without real sleeps
    CooldownTracker(Clock clock) { this.clock = clock; }

    /**
     * Atomically checks whether {@code key} may act again given {@code cooldown}, and if so
     * records the current instant as its new last-use time. A denied attempt performs no
     * mutation — the window always anchors to the last PERMITTED use.
     *
     * @return {@link Duration#ZERO} if acquired; otherwise the remaining wait time (positive).
     */
    public Duration tryAcquire(String key, Duration cooldown) {
        Instant now = clock.instant();
        Instant[] blockedAnchor = new Instant[1]; // non-null iff blocked; holds the OLD (unchanged) timestamp

        lastUse.compute(key, (k, last) -> {
            if (last == null || !now.isBefore(last.plus(cooldown))) {
                return now;          // allowed: first use, or cooldown fully elapsed — record now
            }
            blockedAnchor[0] = last; // blocked: capture the OLD value for the remaining-time calc...
            return last;             // ...and return it UNCHANGED — no mutation, same reference re-stored
        });

        if (blockedAnchor[0] == null) return Duration.ZERO;
        return Duration.between(now, blockedAnchor[0].plus(cooldown)); // remaining computed from the OLD anchor
    }

    /** Caller-scheduled cleanup — no internal thread (library never spawns threads). */
    public void evictOlderThan(Duration age) {
        Instant cutoff = clock.instant().minus(age);
        lastUse.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }
}
```

`tryAcquire` is a single `ConcurrentHashMap.compute()` call — atomic per key, safe when invoked from a
Velocity network thread. `evictOlderThan` uses `entrySet().removeIf(...)`, one of `ConcurrentHashMap`'s
thread-safe collection views — no external locking needed.

## 3. `CommandSpec` / `CommandSpec.Builder`

```java
// Builder
public Builder<S> cooldown(CooldownTracker tracker, Supplier<Duration> duration, BiConsumer<S, Duration> onCooldown)
```

- `tracker`: caller-owned — the plugin constructs and holds it (typically one field per gated command),
  and is responsible for periodically calling `evictOlderThan(...)` on it, exactly as `HelpOpCooldown`
  does today. One tracker maps to one command; its keys are bare sender identities because it's already
  scoped to a single command.
- `duration`: `Supplier<Duration>`, read fresh on every `tryAcquire` call. A config-driven cooldown
  length can change without rebuilding the `CommandSpec` tree — the tracker's accumulated state
  (last-use timestamps) lives in a separate object and is unaffected by any tree rebuild anyway.
- `onCooldown`: **required, non-null** — same convention as `onFail` in `requires()`/`playerOnly()`.
  `BiConsumer<S, Duration>` — sender plus the remaining wait.

All three parameters are validated with `Objects.requireNonNull` inside `cooldown(...)`, matching
`requires()`/`playerOnly()`'s existing validation style. `duration.get()` returning zero or negative is
not rejected — `tryAcquire`'s comparison (`!now.isBefore(last.plus(cooldown))`) already treats a
non-positive cooldown as "always allowed" with no special-casing needed, so a `Supplier<Duration>` that
resolves to zero effectively disables the gate for that check without a separate code path.

`CommandSpec` gains three nullable fields + getters (`cooldownTracker()`, `cooldownDuration()`,
`onCooldown()`), mirroring the existing `requirement()` / `onRequirementFail()` pair exactly.

## 4. `CommandDispatcher` integration

One new line in `execute()`'s per-node gate block, after `playerOnly`, **not** touching `complete()`/`canAccess()`:

```java
if (n.cooldownTracker() != null && !checkCooldown(n, sender, effError)) return;
```

```java
private boolean checkCooldown(CommandSpec<S> n, S sender, BiConsumer<S, Throwable> effError) {
    try {
        String key = adapter.identity(sender);
        if (key == null) return true;              // no identity → exempt

        Duration remaining = n.cooldownTracker().tryAcquire(key, n.cooldownDuration().get());
        if (remaining.isZero() || remaining.isNegative()) return true;  // acquired

        guard(() -> n.onCooldown().accept(sender, remaining), effError, sender);
        return false;
    } catch (Throwable t) {
        guardThrowable(effError, sender, t);
        return false;
    }
}
```

Mirrors the error-handling shape of the existing `gate()` helper (any thrown `Throwable` routes to
`onError`, else is swallowed), but is its own method — `gate()` is boolean-`BooleanSupplier`-shaped,
while this decision hinges on a `Duration`.

Consuming the cooldown happens at gate-pass time, inside `tryAcquire`, *before* the handler runs —
consistent with the existing `gate()` pattern (permission/requirement/playerOnly are all consumed on
check, not on post-hoc success) and matching `ReloadGate`'s current behavior: a handler that throws
still consumes the cooldown.

## Affected files

| File | Change |
|---|---|
| `SenderAdapter.java` | + `default identity()` |
| `PaperSenderAdapter.java`, `VelocitySenderAdapter.java` | `identity()` override |
| `CooldownTracker.java` | **new file** |
| `CommandSpec.java` | + 3 fields/getters, `Builder.cooldown(...)` |
| `CommandDispatcher.java` | + `checkCooldown()`, 1 line in `execute()`'s gate block |
| `FakeSender.java` (test) | `identity()` override |

## Testing plan

**`CooldownTrackerTest`** (new, uses the package-private `Clock`-injecting constructor):
- `firstUseIsAlwaysAllowed`
- `secondUseWithinCooldownIsBlockedAndReportsRemaining`
- `useAfterCooldownElapsesIsAllowedAgain`
- `blockedAttemptDoesNotExtendTheWindow` — two blocked attempts at different times against the same
  original acquisition return monotonically shrinking remaining durations, never reset to the full
  cooldown. This is the single most important behavior in the spec; it gets its own named test, not
  incidental coverage.
- `evictOlderThanRemovesStaleKeysOnly` — a key untouched longer than the eviction age is removed; a
  recently-touched key survives.
- `concurrentTryAcquireForSameKeyOnlyAllowsOne` — a stress/race test hammering `tryAcquire` for one key
  from multiple threads asserts exactly one `Duration.ZERO` result (proves `compute()` atomicity).

**`CommandDispatcherExecuteTest`** (extend existing):
- `cooldownBlocksSecondCallWithinWindow`
- `cooldownAllowsCallAfterWindowElapses`
- `cooldownNotConsumedByPermissionDenial` / `...ByPlayerOnlyDenial` / `...ByUnknownSubcommand` — gate
  order correctness.
- `cooldownConsumedEvenWhenHandlerThrows`
- `nullIdentityExemptsSenderFromCooldown`
- `cooldownOnCooldownReceivesSenderAndRemaining`

**`CommandDispatcherCompleteTest`** (extend existing):
- `tabCompletionDoesNotConsumeCooldown` — completing a cooldown-gated command repeatedly, then
  executing it, must still succeed (proves `complete()`/`canAccess()` never touches the tracker).

## Out of scope

- Config write-back, Managed Executor, soft-dependency helper, PAPI auto-hook — separate specs.
- Migrating `PAxAuth`'s `ReloadGate` or `PAxStaffUtils`'s `HelpOpCooldown` onto this — follow-up work in
  those repos, not part of this library change.
