# Managed Executors — Design Spec

## Motivation

The same "named daemon thread pool + graceful shutdown" pattern is hand-written four times
in the PAxAuth codebase today:

1. `PAXAuthPaper.java` (`newAuthExecutor()`) — `Executors.newFixedThreadPool(poolSize, tf)`,
   `tf` a custom `ThreadFactory` naming threads `"PAxAuth-Async-" + seq.incrementAndGet()`, daemon.
2. `PAXAuthPaper.java` (`dbWatchdogScheduler`) — `Executors.newSingleThreadScheduledExecutor(tf)`,
   `tf` naming the single thread `"PAxAuth-DB-Watchdog"`, daemon.
3. `PAXAuthVelocity.java` (`reconciliationExecutor`) — `Executors.newSingleThreadExecutor(tf)`,
   `tf` naming the single thread `"paxauth-reconciliation"`, daemon.
4. `PAXAuthVelocity.java` (`dbWatchdogScheduler`) — same shape as (2), Velocity side.

All four shut down with the same three steps: `shutdown()` → `awaitTermination(timeout)` → if not
drained, `shutdownNow()` (+ in two of the four, a log message). The only real differences across
the four are the timeout (`500ms` for `authExecutor`/both `dbWatchdogScheduler`s, `10s` for
`reconciliationExecutor`) and whether a forced-shutdown log fires at all:

- `authExecutor` and `reconciliationExecutor` **do** log a warning, but only on the
  `awaitTermination` timeout branch — the `catch (InterruptedException e)` branch silently calls
  `shutdownNow()` with no log.
- Both `dbWatchdogScheduler` instances (Paper and Velocity) **log nothing at all**, on either
  branch — they just shut down silently.

This confirms the pattern is copy-pasted (not parameterized) and that the two existing behaviors
(log-on-timeout-only vs. never-log) are themselves inconsistent — an artifact of copy/paste, not a
deliberate design distinction.

## Scope

Add a `ManagedExecutors` utility class to PAxAPI providing:
- Named daemon-thread executor construction (fixed pool, single-thread, single-thread-scheduled).
- A single graceful-shutdown helper usable for all of them.

This does **not** create a new owning abstraction — callers still hold and pass around plain
`ExecutorService` / `ScheduledExecutorService` references, exactly as they do today. It only
removes the boilerplate around *constructing* and *tearing down* those references.

## Package

New package: `hu.patriksgit.paxapi.concurrent`. This is cross-cutting infrastructure that doesn't
belong under any existing module (`database`, `config`, `command`, `sound`, `text`).

## Why static factory methods, not a wrapper type

A `ManagedExecutor` wrapper class was considered and rejected: `Database.watchConnection(
ScheduledExecutorService, ...)` requires the raw JDK type. A wrapper would need an "unwrap"
accessor for that interop, which would erase exactly what the wrapper was meant to gain. Static
factories return the raw JDK types directly, so the caller's storage and downstream interop
(e.g. `watchConnection`) are unchanged from what they do today.

## API

```java
package hu.patriksgit.paxapi.concurrent;

public final class ManagedExecutors {
    private ManagedExecutors() {}

    public static ExecutorService fixed(String namePrefix, int poolSize) { ... }
    public static ExecutorService singleThread(String name) { ... }
    public static ScheduledExecutorService singleThreadScheduled(String name) { ... }

    public static void shutdownGracefully(ExecutorService executor, Duration timeout,
                                           Runnable onForcedShutdown) { ... }
}
```

### `fixed(namePrefix, poolSize)`

`Executors.newFixedThreadPool(poolSize, tf)`, where `tf` produces daemon threads named
`namePrefix + "-" + seq.incrementAndGet()` (mirrors `authExecutor`'s naming exactly).

- `poolSize <= 0` → `IllegalArgumentException`. No automatic sizing heuristic (e.g.
  `Math.max(2, cores)`) — that decision depends on workload shape (CPU-bound Argon2 hashing vs.
  I/O-bound DB calls), which the library must not assume. The caller supplies an explicit size.
- `namePrefix` `null` or blank → `IllegalArgumentException`.

### `singleThread(name)`

`Executors.newSingleThreadExecutor(tf)`, where `tf` produces one daemon thread named exactly
`name` (no suffix — mirrors `reconciliationExecutor`, which wants one fixed name, not a sequence).

- `name` `null` or blank → `IllegalArgumentException`.

### `singleThreadScheduled(name)`

Same as `singleThread`, but `Executors.newSingleThreadScheduledExecutor(tf)` (mirrors
`dbWatchdogScheduler`).

- `name` `null` or blank → `IllegalArgumentException`.

All three validations are fail-fast (`IllegalArgumentException` at construction), not deferred
into a lazily-thrown NPE somewhere inside the `ThreadFactory` — consistent with the library's
existing fail-fast style (e.g. `CooldownTracker`'s `Objects.requireNonNull` guards).

### `shutdownGracefully(executor, timeout, onForcedShutdown)`

```java
public static void shutdownGracefully(ExecutorService executor, Duration timeout, Runnable onForcedShutdown) {
    Objects.requireNonNull(executor, "executor");
    Objects.requireNonNull(timeout, "timeout");
    executor.shutdown();
    try {
        if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            executor.shutdownNow();
            if (onForcedShutdown != null) {
                try { onForcedShutdown.run(); } catch (Throwable ignored) { }
            }
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        executor.shutdownNow();
        if (onForcedShutdown != null) {
            try { onForcedShutdown.run(); } catch (Throwable ignored) { }
        }
    }
}
```

Decisions this encodes, each confirmed explicitly against the evidence above:

1. **`onForcedShutdown` is `Runnable`, not `(Logger, String)`.** All three real call sites that log
   today do nothing but log a static message — no parameters needed inside the callback. A
   `Runnable` matches the library's existing preference for plain functional interfaces
   (`CooldownTracker`'s `BiConsumer<S, Duration>`, `Database`'s `Consumer<Boolean>`) over
   bespoke parameter tuples.
2. **The callback fires on BOTH branches** (timeout *and* `InterruptedException`), not only the
   timeout branch. Both branches end in `shutdownNow()` — from an operator's perspective both are
   "forced shutdown" events. The current silent-on-interrupt behavior in `authExecutor` /
   `reconciliationExecutor` is treated as a copy/paste gap, not a deliberate asymmetry worth
   preserving.
3. **`onForcedShutdown` is nullable.** Both `dbWatchdogScheduler` call sites want exactly this: no
   log, no callback, ever. Making the parameter mandatory would force those two call sites to write
   a no-op lambda for no benefit — pure boilerplate against a purely diagnostic parameter. (This is
   NOT the same situation as `CommandSpec.Builder.cooldown`'s mandatory `onCooldown` — that
   callback carries the feature's primary denial-handling signal and must not be silently
   swallowed. `onForcedShutdown` is optional operational logging with no behavioral consequence.)
4. **Interrupt handling lives inside the helper, not the caller.** The
   `catch (InterruptedException e) { Thread.currentThread().interrupt(); executor.shutdownNow(); }`
   pattern is identical, verbatim, across all four existing call sites — it belongs in one place.
5. **`shutdownGracefully` never throws.** `shutdown()` / `awaitTermination()` / `shutdownNow()`
   don't throw uncaught per the JDK contract (the one checked exception, `InterruptedException`,
   is caught); `onForcedShutdown.run()` is wrapped in `catch (Throwable ignored)` so a broken
   caller-supplied callback cannot prevent `shutdownNow()` from having already run, or propagate
   out of a plugin's `onDisable()`/`onShutdown()` cleanup chain (matching the
   `try/catch(Throwable ignored)` convention already used throughout `onDisable()` in PAxAuth and
   inside `Database` itself, e.g. `close()`, `watchConnection`'s guarded callback).

Ordering note (non-blocking, recorded for posterity): within the timeout branch, this calls
`shutdownNow()` *before* `onForcedShutdown.run()`, whereas the original `authExecutor` code logged
*before* calling `shutdownNow()`. This has no observable effect on any of today's three real call
sites, since none of their messages read executor state. If a future caller's callback needs to
observe pre-`shutdownNow()` executor state, this ordering would need revisiting — out of scope
for now.

## Out of scope / non-goals

- No automatic pool-size sizing heuristic in `fixed()` — the caller always supplies an explicit
  size (workload shape is caller-specific: CPU-bound vs. I/O-bound).
- No `ManagedExecutor` wrapper type (see rationale above).
- No change to `Database.watchConnection` or any other existing API — this is purely additive.
- No migration of PAxAuth's four call sites is part of this PAxAPI change; that's a follow-up in
  the PAxAuth codebase once this ships.
- No thread-priority, rejection-policy, or queue-capacity configuration — none of the four known
  call sites need it, and adding it now would be speculative API surface.

## Testing plan — `ManagedExecutorsTest`

**`fixed(namePrefix, poolSize)`**
- `fixedCreatesPoolOfRequestedSizeWithDaemonNamedThreads` — submit `poolSize` tasks synchronized
  via a `CountDownLatch`, each task records `Thread.currentThread()`; assert all are daemon and
  named `namePrefix + "-" + N`.
- `fixedRejectsNonPositivePoolSize` — `poolSize = 0` and `poolSize = -1` → `IllegalArgumentException`.
- `fixedRejectsNullOrBlankNamePrefix` — `null` and `""` → `IllegalArgumentException`.

**`singleThread(name)`**
- `singleThreadCreatesExactlyOneDaemonThreadNamedExactly` — the submitted task records the thread
  name; asserts it equals `name` exactly (no suffix), and is daemon.
- `singleThreadRejectsNullOrBlankName` — `IllegalArgumentException`.

**`singleThreadScheduled(name)`**
- `singleThreadScheduledSupportsRepeatingSchedule` — `scheduleAtFixedRate` (or
  `scheduleWithFixedDelay`) a task that counts down a `CountDownLatch(3)`; awaits the latch with a
  bounded timeout and asserts it reaches zero, proving the returned scheduler actually supports
  *repeating* execution (its primary real consumer, `Database.watchConnection`, calls
  `scheduleAtFixedRate` and is inherently periodic — a single-run test would not validate the
  capability that's actually needed). Also asserts the executing thread is daemon and named exactly
  `name`.
- `singleThreadScheduledRejectsNullOrBlankName` — `IllegalArgumentException`.

**`shutdownGracefully(executor, timeout, onForcedShutdown)`**
- `drainsWithinTimeoutWithoutCallingForcedShutdown` — a fast task, generous timeout →
  `onForcedShutdown` never runs (tracked via `AtomicBoolean`); `executor.isTerminated()` is true.
- `callsForcedShutdownWhenTimeoutElapses` — a task blocked on a `CountDownLatch` that's never
  counted down, short timeout (e.g. 50ms) → `onForcedShutdown` runs exactly once;
  `executor.isShutdown()` is true.
- `nullOnForcedShutdownIsToleratedOnTimeoutBranch` — same setup, `onForcedShutdown = null` → no
  NPE, shutdown still proceeds.
- `throwingOnForcedShutdownIsSwallowed` — callback throws `RuntimeException` →
  `shutdownGracefully` does not propagate it, returns normally.
- `interruptedWhileAwaitingCallsForcedShutdownAndRestoresInterruptStatus` — run
  `shutdownGracefully` on a dedicated spawned thread against an executor running a long-blocking
  task, with a long timeout; after a short delay, interrupt that spawned thread (not any
  executor-internal task thread — the two must be kept clearly distinct, since interrupting an
  in-flight executor task is irrelevant to this path). After `join()`, assert (a) `onForcedShutdown`
  ran, and (b) the spawned thread had recorded `Thread.currentThread().isInterrupted() == true`
  into an `AtomicBoolean` immediately after `shutdownGracefully` returned, before the thread exited
  (the flag cannot be read back from the test's main thread afterwards).
- `nullOnForcedShutdownIsToleratedOnInterruptedBranch` — same as above, `onForcedShutdown = null`
  → no NPE.
- `shutdownGracefullyRejectsNullExecutor` — `NullPointerException`.
- `shutdownGracefullyRejectsNullTimeout` — `NullPointerException`.

## Affected files

| File | Change |
|---|---|
| `src/main/java/hu/patriksgit/paxapi/concurrent/ManagedExecutors.java` | New. |
| `src/test/java/hu/patriksgit/paxapi/concurrent/ManagedExecutorsTest.java` | New. |
| `API.md` | New section documenting `ManagedExecutors`. |
