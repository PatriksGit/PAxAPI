# Command per-key cooldown gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a first-class per-key cooldown gate to `CommandSpec.Builder` (`.cooldown(...)`) backed by a new standalone `CooldownTracker` class, so downstream plugins stop hand-rolling this on top of the command framework.

**Architecture:** A new `CooldownTracker` (plain `String`-keyed, `ConcurrentHashMap`-backed, atomic `tryAcquire`/`evictOlderThan`) is wired into `CommandDispatcher.execute()` as a fourth gate — after `permission` → `requirement` → `playerOnly` — via a new `checkCooldown()` method. `SenderAdapter<S>` gains a `default identity(S)` method (null-safe, non-breaking) that supplies the tracker's key. `complete()`/`canAccess()` are never touched, so tab-completion never consumes a cooldown.

**Tech Stack:** Java 21, JUnit 5.10.2 (Jupiter), Mockito 5.11.0, Maven.

**Spec:** `docs/superpowers/specs/2026-07-12-command-cooldown-gate-design.md`

## Global Constraints

- Java 21 / JUnit Jupiter 5.10.2 / Mockito 5.11.0 (from `pom.xml`) — match existing test style exactly (see `CommandDispatcherExecuteTest.java`, `CommandSpecTest.java`).
- The library never spawns its own threads — `CooldownTracker.evictOlderThan` is caller-scheduled, no internal executor/timer.
- `CommandSpec` stays immutable — all new state is nullable fields set once in the constructor from the `Builder`, exactly like `requirement`/`onRequirementFail`.
- Cooldown is consumed **only** in `CommandDispatcher.execute()`, **never** in `complete()`/`canAccess()`.
- Gate order is `permission` → `requirement` → `playerOnly` → `cooldown`, in that exact sequence, per node, during tree traversal.
- A blocked `tryAcquire` call must mutate nothing — the window always anchors to the last *permitted* use.
- All `Builder` parameter validation uses `Objects.requireNonNull(x, "paramName")`, matching `requires()`/`playerOnly()`.

---

## Task 1: `CooldownTracker`

**Files:**
- Create: `src/main/java/hu/patriksgit/paxapi/command/CooldownTracker.java`
- Test: `src/test/java/hu/patriksgit/paxapi/command/CooldownTrackerTest.java`

**Interfaces:**
- Produces: `public final class CooldownTracker` with `public CooldownTracker()`, package-private `CooldownTracker(Clock clock)`, `public Duration tryAcquire(String key, Duration cooldown)`, `public void evictOlderThan(Duration age)`. Task 4 depends on `tryAcquire`/`evictOlderThan`'s exact signatures.

- [ ] **Step 1: Write the failing test file**

Create `src/test/java/hu/patriksgit/paxapi/command/CooldownTrackerTest.java`:

```java
package hu.patriksgit.paxapi.command;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownTrackerTest {

    /** Test-only mutable clock — lets tests advance time deterministically without real sleeps. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test void firstUseIsAlwaysAllowed() {
        CooldownTracker tracker = new CooldownTracker(new MutableClock(Instant.parse("2024-01-01T00:00:00Z")));
        assertEquals(Duration.ZERO, tracker.tryAcquire("k", Duration.ofSeconds(10)));
    }

    @Test void secondUseWithinCooldownIsBlockedAndReportsRemaining() {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        CooldownTracker tracker = new CooldownTracker(clock);
        Duration cooldown = Duration.ofSeconds(10);

        assertEquals(Duration.ZERO, tracker.tryAcquire("k", cooldown));
        clock.advance(Duration.ofSeconds(4));
        assertEquals(Duration.ofSeconds(6), tracker.tryAcquire("k", cooldown));
    }

    @Test void useAfterCooldownElapsesIsAllowedAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        CooldownTracker tracker = new CooldownTracker(clock);
        Duration cooldown = Duration.ofSeconds(10);

        assertEquals(Duration.ZERO, tracker.tryAcquire("k", cooldown));
        clock.advance(Duration.ofSeconds(10));
        assertEquals(Duration.ZERO, tracker.tryAcquire("k", cooldown));
    }

    // The single most important behavior in the spec: a blocked attempt must never reset or
    // extend the window. Two blocked checks at different times must return monotonically
    // shrinking remaining durations, anchored to the ORIGINAL acquisition — never reset to the
    // full cooldown.
    @Test void blockedAttemptDoesNotExtendTheWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        CooldownTracker tracker = new CooldownTracker(clock);
        Duration cooldown = Duration.ofSeconds(10);

        assertEquals(Duration.ZERO, tracker.tryAcquire("k", cooldown));   // first use: allowed

        clock.advance(Duration.ofSeconds(3));
        assertEquals(Duration.ofSeconds(7), tracker.tryAcquire("k", cooldown)); // blocked, 7s left

        clock.advance(Duration.ofSeconds(2));
        assertEquals(Duration.ofSeconds(5), tracker.tryAcquire("k", cooldown)); // blocked again — 5s, NOT reset to 10s
    }

    @Test void evictOlderThanRemovesStaleKeysOnly() {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        CooldownTracker tracker = new CooldownTracker(clock);

        tracker.tryAcquire("stale", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(30));
        tracker.tryAcquire("fresh", Duration.ofSeconds(1));

        tracker.evictOlderThan(Duration.ofSeconds(20));

        // "stale" was last touched 30s ago (older than the 20s eviction age) -> evicted -> treated as first use again
        assertEquals(Duration.ZERO, tracker.tryAcquire("stale", Duration.ofSeconds(1)));
        // "fresh" was just touched -> NOT evicted -> still on cooldown
        Duration remaining = tracker.tryAcquire("fresh", Duration.ofSeconds(1));
        assertTrue(remaining.compareTo(Duration.ZERO) > 0, "fresh key should still be on cooldown, got " + remaining);
    }

    @Test void concurrentTryAcquireForSameKeyOnlyAllowsOne() throws InterruptedException {
        CooldownTracker tracker = new CooldownTracker(); // real clock: this test only cares about atomicity
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                Duration r = tracker.tryAcquire("shared", Duration.ofSeconds(60));
                if (r.isZero()) acquired.incrementAndGet();
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, acquired.get(), "exactly one thread should have acquired the shared key");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=CooldownTrackerTest test`
Expected: compile error — `CooldownTracker` does not exist (`cannot find symbol`).

- [ ] **Step 3: Write the implementation**

Create `src/main/java/hu/patriksgit/paxapi/command/CooldownTracker.java`:

```java
package hu.patriksgit.paxapi.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key cooldown state, keyed by a caller-supplied {@code String} (typically a sender identity
 * from {@link SenderAdapter#identity}). One tracker instance is meant to back one gated command —
 * see {@link CommandSpec.Builder#cooldown}.
 *
 * <p>Never spawns threads: {@link #evictOlderThan} must be called by the owner on its own schedule.
 */
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

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=CooldownTrackerTest test`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hu/patriksgit/paxapi/command/CooldownTracker.java src/test/java/hu/patriksgit/paxapi/command/CooldownTrackerTest.java
git commit -m "feat(command): add CooldownTracker"
```

---

## Task 2: `SenderAdapter.identity()` + platform adapters + `FakeSender`

**Files:**
- Modify: `src/main/java/hu/patriksgit/paxapi/command/SenderAdapter.java`
- Modify: `src/main/java/hu/patriksgit/paxapi/command/paper/PaperSenderAdapter.java`
- Modify: `src/main/java/hu/patriksgit/paxapi/command/velocity/VelocitySenderAdapter.java`
- Modify: `src/test/java/hu/patriksgit/paxapi/command/FakeSender.java`
- Test: `src/test/java/hu/patriksgit/paxapi/command/paper/PaperSenderAdapterTest.java` (new)
- Test: `src/test/java/hu/patriksgit/paxapi/command/velocity/VelocitySenderAdapterTest.java` (new)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `SenderAdapter<S>.identity(S sender)` (default, returns `null`); `FakeSender.ADAPTER.identity(...)` and a mutable `FakeSender.id` field. Task 4 depends on both.

- [ ] **Step 1: Write the failing test files**

Create `src/test/java/hu/patriksgit/paxapi/command/paper/PaperSenderAdapterTest.java`:

```java
package hu.patriksgit.paxapi.command.paper;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperSenderAdapterTest {

    @Test void identityReturnsPlayerUuidAsString() {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        assertEquals(id.toString(), PaperSenderAdapter.INSTANCE.identity(p));
    }

    @Test void identityReturnsNullForNonPlayerSender() {
        CommandSender sender = mock(CommandSender.class);
        assertNull(PaperSenderAdapter.INSTANCE.identity(sender));
    }
}
```

Create `src/test/java/hu/patriksgit/paxapi/command/velocity/VelocitySenderAdapterTest.java`:

```java
package hu.patriksgit.paxapi.command.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VelocitySenderAdapterTest {

    @Test void identityReturnsPlayerUuidAsString() {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        assertEquals(id.toString(), VelocitySenderAdapter.INSTANCE.identity(p));
    }

    @Test void identityReturnsNullForNonPlayerSender() {
        CommandSource sender = mock(CommandSource.class);
        assertNull(VelocitySenderAdapter.INSTANCE.identity(sender));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=PaperSenderAdapterTest,VelocitySenderAdapterTest test`
Expected: compile error — `identity` is not a method on `SenderAdapter`/`PaperSenderAdapter`/`VelocitySenderAdapter`.

- [ ] **Step 3: Implement `identity()` everywhere**

Modify `src/main/java/hu/patriksgit/paxapi/command/SenderAdapter.java` — replace the whole file:

```java
package hu.patriksgit.paxapi.command;

/** Platform abstraction the dispatcher needs. Implemented by each platform adapter. */
public interface SenderAdapter<S> {
    boolean hasPermission(S sender, String permission);
    boolean isPlayer(S sender);

    /**
     * Stable identity for cooldown-keying, or {@code null} if the sender has none (console,
     * command block, RCON). A {@code null} identity exempts the sender from cooldown checks
     * entirely — it is NOT bucketed together with other identity-less senders.
     *
     * <p>Default implementation returns {@code null} for every sender, so adapters written
     * before this method existed keep compiling and simply opt out of cooldown gating.
     */
    default String identity(S sender) { return null; }
}
```

Modify `src/main/java/hu/patriksgit/paxapi/command/paper/PaperSenderAdapter.java` — replace the whole file:

```java
package hu.patriksgit.paxapi.command.paper;

import hu.patriksgit.paxapi.command.SenderAdapter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PaperSenderAdapter implements SenderAdapter<CommandSender> {
    public static final PaperSenderAdapter INSTANCE = new PaperSenderAdapter();
    private PaperSenderAdapter() {}
    @Override public boolean hasPermission(CommandSender sender, String permission) { return sender.hasPermission(permission); }
    @Override public boolean isPlayer(CommandSender sender) { return sender instanceof Player; }
    @Override public String identity(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId().toString() : null;
    }
}
```

Modify `src/main/java/hu/patriksgit/paxapi/command/velocity/VelocitySenderAdapter.java` — replace the whole file:

```java
package hu.patriksgit.paxapi.command.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import hu.patriksgit.paxapi.command.SenderAdapter;

public final class VelocitySenderAdapter implements SenderAdapter<CommandSource> {
    public static final VelocitySenderAdapter INSTANCE = new VelocitySenderAdapter();
    private VelocitySenderAdapter() {}
    @Override public boolean hasPermission(CommandSource sender, String permission) { return sender.hasPermission(permission); }
    @Override public boolean isPlayer(CommandSource sender) { return sender instanceof Player; }
    @Override public String identity(CommandSource sender) {
        return sender instanceof Player p ? p.getUniqueId().toString() : null;
    }
}
```

Modify `src/test/java/hu/patriksgit/paxapi/command/FakeSender.java` — replace the whole file:

```java
package hu.patriksgit.paxapi.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Test sender: records messages, holds permissions + player flag. */
final class FakeSender {
    final Set<String> permissions = new HashSet<>();
    boolean player;
    // Cooldown identity: UUID string for players, null for console (mirrors the real adapters).
    // Mutable and package-visible so individual tests can force an unusual combination (e.g. a
    // non-player WITH an identity) to isolate one specific gate-ordering behavior.
    String id;
    final List<String> events = new ArrayList<>();

    static FakeSender player(String... perms) { return make(true, UUID.randomUUID().toString(), perms); }
    static FakeSender console(String... perms) { return make(false, null, perms); }
    private static FakeSender make(boolean player, String id, String... perms) {
        FakeSender s = new FakeSender();
        s.player = player;
        s.id = id;
        for (String p : perms) s.permissions.add(p);
        return s;
    }

    static final SenderAdapter<FakeSender> ADAPTER = new SenderAdapter<>() {
        public boolean hasPermission(FakeSender s, String perm) { return s.permissions.contains(perm); }
        public boolean isPlayer(FakeSender s) { return s.player; }
        public String identity(FakeSender s) { return s.id; }
    };
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=PaperSenderAdapterTest,VelocitySenderAdapterTest,CommandDispatcherExecuteTest,CommandDispatcherCompleteTest,CommandSpecTest test`
Expected: `Tests run: <all>, Failures: 0, Errors: 0` — the new adapter tests pass, and every pre-existing command test (including the anonymous `SenderAdapter` doubles that only implement `hasPermission`/`isPlayer`) still compiles and passes unchanged, proving the `default` method is non-breaking.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hu/patriksgit/paxapi/command/SenderAdapter.java \
        src/main/java/hu/patriksgit/paxapi/command/paper/PaperSenderAdapter.java \
        src/main/java/hu/patriksgit/paxapi/command/velocity/VelocitySenderAdapter.java \
        src/test/java/hu/patriksgit/paxapi/command/FakeSender.java \
        src/test/java/hu/patriksgit/paxapi/command/paper/PaperSenderAdapterTest.java \
        src/test/java/hu/patriksgit/paxapi/command/velocity/VelocitySenderAdapterTest.java
git commit -m "feat(command): add SenderAdapter.identity() for cooldown-keying"
```

---

## Task 3: `CommandSpec` / `CommandSpec.Builder.cooldown(...)`

**Files:**
- Modify: `src/main/java/hu/patriksgit/paxapi/command/CommandSpec.java`
- Modify: `src/test/java/hu/patriksgit/paxapi/command/CommandSpecTest.java`

**Interfaces:**
- Consumes: `CooldownTracker` (Task 1) as an opaque type — no method calls on it here.
- Produces: `CommandSpec<S>.cooldownTracker()` → `CooldownTracker`, `.cooldownDuration()` → `Supplier<Duration>`, `.onCooldown()` → `BiConsumer<S, Duration>` (all nullable); `Builder<S>.cooldown(CooldownTracker, Supplier<Duration>, BiConsumer<S, Duration>)` → `Builder<S>`. Task 4 depends on all three getter names/types exactly.

- [ ] **Step 1: Write the failing tests**

Modify `src/test/java/hu/patriksgit/paxapi/command/CommandSpecTest.java` — add these imports right after the existing `import java.util.List;` (line 4):

```java
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
```

Add these test methods at the end of the class, just before the final closing `}`:

```java
    @Test void cooldownRequiresNonNullTracker() {
        assertThrows(NullPointerException.class,
            () -> CommandSpec.root("x").cooldown(null, () -> Duration.ofSeconds(1), (s, r) -> {}));
    }

    @Test void cooldownRequiresNonNullDuration() {
        assertThrows(NullPointerException.class,
            () -> CommandSpec.root("x").cooldown(new CooldownTracker(), null, (s, r) -> {}));
    }

    @Test void cooldownRequiresNonNullOnCooldown() {
        assertThrows(NullPointerException.class,
            () -> CommandSpec.root("x").cooldown(new CooldownTracker(), () -> Duration.ofSeconds(1), null));
    }

    @Test void cooldownFieldsAccessibleAfterBuild() {
        CooldownTracker tracker = new CooldownTracker();
        Supplier<Duration> duration = () -> Duration.ofSeconds(5);
        BiConsumer<Object, Duration> onCooldown = (s, r) -> {};
        CommandSpec<Object> spec = CommandSpec.root("x").cooldown(tracker, duration, onCooldown).build();
        assertSame(tracker, spec.cooldownTracker());
        assertSame(duration, spec.cooldownDuration());
        assertSame(onCooldown, spec.onCooldown());
    }

    @Test void nullCooldownFieldsWhenNotConfigured() {
        CommandSpec<Object> spec = CommandSpec.root("x").build();
        assertNull(spec.cooldownTracker());
        assertNull(spec.cooldownDuration());
        assertNull(spec.onCooldown());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=CommandSpecTest test`
Expected: compile error — `cooldown`, `cooldownTracker`, `cooldownDuration`, `onCooldown` are not members of `CommandSpec`/`Builder`.

- [ ] **Step 3: Implement the fields, getters, and builder method**

Modify `src/main/java/hu/patriksgit/paxapi/command/CommandSpec.java`.

Replace the import block (lines 1–10) with:

```java
package hu.patriksgit.paxapi.command;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
```

Replace the three `CommandSpec` field declarations (lines 26–28):

```java
    private final Consumer<S> onDenied;                 // nullable
    private final BiConsumer<S, String> onUnknown;      // nullable
    private final BiConsumer<S, Throwable> onError;     // nullable
```

with:

```java
    private final Consumer<S> onDenied;                 // nullable
    private final BiConsumer<S, String> onUnknown;      // nullable
    private final BiConsumer<S, Throwable> onError;     // nullable
    private final CooldownTracker cooldownTracker;        // nullable
    private final Supplier<Duration> cooldownDuration;    // non-null iff cooldownTracker != null
    private final BiConsumer<S, Duration> onCooldown;      // non-null iff cooldownTracker != null
```

In the constructor, replace line 54 (`this.onError = b.onError;`) with:

```java
        this.onError = b.onError;
        this.cooldownTracker = b.cooldownTracker;
        this.cooldownDuration = b.cooldownDuration;
        this.onCooldown = b.onCooldown;
```

Replace the `onError()` getter (line 72):

```java
    public BiConsumer<S, Throwable> onError() { return onError; }
```

with:

```java
    public BiConsumer<S, Throwable> onError() { return onError; }
    public CooldownTracker cooldownTracker() { return cooldownTracker; }
    public Supplier<Duration> cooldownDuration() { return cooldownDuration; }
    public BiConsumer<S, Duration> onCooldown() { return onCooldown; }
```

In the `Builder` class, replace the three `Builder` field declarations (lines 85–87):

```java
        private Consumer<S> onDenied;
        private BiConsumer<S, String> onUnknown;
        private BiConsumer<S, Throwable> onError;
```

with:

```java
        private Consumer<S> onDenied;
        private BiConsumer<S, String> onUnknown;
        private BiConsumer<S, Throwable> onError;
        private CooldownTracker cooldownTracker;
        private Supplier<Duration> cooldownDuration;
        private BiConsumer<S, Duration> onCooldown;
```

Replace the `onError(...)` builder method (line 166):

```java
        public Builder<S> onError(BiConsumer<S, Throwable> c) { this.onError = c; return this; }
```

with:

```java
        public Builder<S> onError(BiConsumer<S, Throwable> c) { this.onError = c; return this; }
        /**
         * Gates this node behind a per-key cooldown. {@code tracker} is caller-owned — construct
         * and hold one {@link CooldownTracker} per gated command, and call its
         * {@link CooldownTracker#evictOlderThan} on your own schedule; this library never spawns
         * threads. {@code duration} is read fresh on every check, so a config-driven cooldown
         * length can change without rebuilding this spec. {@code onCooldown} is required — it
         * receives the sender and the remaining wait whenever a check is blocked.
         *
         * <p>The cooldown is consumed by {@link CommandDispatcher#execute}, never by
         * {@link CommandDispatcher#complete} — merely tab-completing this command never counts as
         * a use. It is checked last, after permission/requirement/playerOnly all pass.
         */
        public Builder<S> cooldown(CooldownTracker tracker, Supplier<Duration> duration, BiConsumer<S, Duration> onCooldown) {
            this.cooldownTracker = Objects.requireNonNull(tracker, "tracker");
            this.cooldownDuration = Objects.requireNonNull(duration, "duration");
            this.onCooldown = Objects.requireNonNull(onCooldown, "onCooldown");
            return this;
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=CommandSpecTest test`
Expected: `Tests run: <all>, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hu/patriksgit/paxapi/command/CommandSpec.java src/test/java/hu/patriksgit/paxapi/command/CommandSpecTest.java
git commit -m "feat(command): add CommandSpec.Builder.cooldown(...)"
```

---

## Task 4: `CommandDispatcher` integration

**Files:**
- Modify: `src/main/java/hu/patriksgit/paxapi/command/CommandDispatcher.java`
- Modify: `src/test/java/hu/patriksgit/paxapi/command/CommandDispatcherExecuteTest.java`
- Modify: `src/test/java/hu/patriksgit/paxapi/command/CommandDispatcherCompleteTest.java`

**Interfaces:**
- Consumes: `CooldownTracker.tryAcquire(String,Duration)` (Task 1); `SenderAdapter.identity(S)` (Task 2); `CommandSpec.cooldownTracker()/cooldownDuration()/onCooldown()` and `Builder.cooldown(...)` (Task 3); `FakeSender.id` field (Task 2).
- Produces: nothing consumed by a later task — this is the last task.

- [ ] **Step 1: Write the failing tests**

Modify `src/test/java/hu/patriksgit/paxapi/command/CommandDispatcherExecuteTest.java` — add this import right after `import org.junit.jupiter.api.Test;` (line 3):

```java
import java.time.Duration;
```

Add these test methods at the end of the class, just before the final closing `}`:

```java
    @Test void cooldownBlocksSecondCallWithinWindow() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "x", new String[0]);
        dispatcher(spec).execute(s, "x", new String[0]);
        assertEquals(java.util.List.of("handled", "cooldown"), s.events);
    }

    @Test void cooldownAllowsCallAfterWindowElapses() throws InterruptedException {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .cooldown(tracker, () -> Duration.ofMillis(50), (s, remaining) -> s.events.add("cooldown"))
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "x", new String[0]);
        Thread.sleep(80);
        dispatcher(spec).execute(s, "x", new String[0]);
        assertEquals(java.util.List.of("handled", "handled"), s.events);
    }

    // Gate order: permission fails before the cooldown line is ever reached, so a denied
    // attempt must leave the cooldown completely fresh for the next (permitted) attempt.
    @Test void cooldownNotConsumedByPermissionDenial() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .permission("x.use")
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender s = FakeSender.player(); // lacks x.use
        dispatcher(spec).execute(s, "x", new String[0]); // permission gate fails first; cooldown line never reached
        s.permissions.add("x.use");
        dispatcher(spec).execute(s, "x", new String[0]); // now passes permission; cooldown must still be fresh
        assertEquals(java.util.List.of("handled"), s.events);
    }

    // Same gate-order concern as above, but for playerOnly. The denied sender deliberately gets
    // a non-null identity (a combination no REAL adapter ever produces — only players have one)
    // purely to make this test meaningful: with the default null identity, a wrongly-ordered
    // cooldown check would be masked by the null-identity exemption instead of being caught here.
    @Test void cooldownNotConsumedByPlayerOnlyDenial() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .playerOnly(s -> {})
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender nonPlayer = FakeSender.console();
        nonPlayer.id = java.util.UUID.randomUUID().toString();
        dispatcher(spec).execute(nonPlayer, "x", new String[0]); // playerOnly fails first; cooldown line never reached

        FakeSender player = FakeSender.player();
        player.id = nonPlayer.id; // same tracker key as the denied attempt above
        dispatcher(spec).execute(player, "x", new String[0]); // must still succeed
        assertEquals(java.util.List.of("handled"), player.events);
    }

    // "Unknown subcommand never consumes it" is a routing concern, not a same-node code-order
    // concern: the cooldown-gated leaf is simply never VISITED when an unrelated top-level token
    // doesn't match any child, so its cooldown is never evaluated at all.
    @Test void cooldownNotConsumedByUnknownSubcommand() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .group("known", g -> g
                .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
                .handler(ctx -> ctx.sender().events.add("handled")))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "x", new String[]{"bogus"}); // unknown top-level token; "known" leaf never visited
        dispatcher(spec).execute(s, "x", new String[]{"known"}); // first real visit to the cooldown-gated leaf
        assertEquals(java.util.List.of("handled"), s.events);
    }

    @Test void cooldownConsumedEvenWhenHandlerThrows() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
            .onError((s, t) -> s.events.add("error"))
            .handler(ctx -> { throw new IllegalStateException("boom"); })
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "x", new String[0]); // handler throws, routed to onError
        dispatcher(spec).execute(s, "x", new String[0]); // cooldown still active even though the first handler failed
        assertEquals(java.util.List.of("error", "cooldown"), s.events);
    }

    @Test void nullIdentityExemptsSenderFromCooldown() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender s = FakeSender.player();
        s.id = null; // simulate a sender with no resolvable identity (e.g. console/RCON in real adapters)
        dispatcher(spec).execute(s, "x", new String[0]);
        dispatcher(spec).execute(s, "x", new String[0]); // would be blocked if not exempt
        assertEquals(java.util.List.of("handled", "handled"), s.events);
    }

    @Test void cooldownOnCooldownReceivesSenderAndRemaining() {
        CooldownTracker tracker = new CooldownTracker();
        java.util.List<Duration> seen = new java.util.ArrayList<>();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> { s.events.add("cooldown"); seen.add(remaining); })
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "x", new String[0]);
        dispatcher(spec).execute(s, "x", new String[0]);
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).compareTo(Duration.ZERO) > 0, "remaining must be positive, got " + seen.get(0));
        assertTrue(seen.get(0).compareTo(Duration.ofMinutes(10)) <= 0, "remaining must not exceed the configured cooldown, got " + seen.get(0));
    }
```

Modify `src/test/java/hu/patriksgit/paxapi/command/CommandDispatcherCompleteTest.java` — add this import right after `import org.junit.jupiter.api.Test;` (line 3):

```java
import java.time.Duration;
```

Add this test method at the end of the class, just before the final closing `}`:

```java
    @Test void tabCompletionDoesNotConsumeCooldown() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender s = FakeSender.player();
        CommandDispatcher<FakeSender> d = dispatcher(spec);
        for (int i = 0; i < 5; i++) d.complete(s, new String[]{""}); // repeated tab-completion must never touch the tracker
        d.execute(s, "x", new String[0]);
        assertEquals(List.of("handled"), s.events);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=CommandDispatcherExecuteTest,CommandDispatcherCompleteTest test`
Expected: compile error — `cooldown(...)` is unresolved on `CommandSpec.Builder` is now resolved (Task 3 landed it), but `checkCooldown` gate wiring doesn't exist yet, so cooldown tests fail on assertions (e.g. `cooldownBlocksSecondCallWithinWindow` sees `["handled", "handled"]` instead of `["handled", "cooldown"]`) rather than a compile error.

- [ ] **Step 3: Implement the dispatcher gate**

Modify `src/main/java/hu/patriksgit/paxapi/command/CommandDispatcher.java`.

Replace the import block (lines 1–9) with:

```java
package hu.patriksgit.paxapi.command;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
```

Replace the gate block inside `execute()` (lines 44–47):

```java
            final CommandSpec<S> n = node;
            if (n.permission() != null && !gate(() -> adapter.hasPermission(sender, n.permission()), effDenied, sender, effError)) return;
            if (n.requirement() != null && !gate(() -> n.requirement().test(sender), n.onRequirementFail(), sender, effError)) return;
            if (n.playerOnly() && !gate(() -> adapter.isPlayer(sender), n.onPlayerOnlyFail(), sender, effError)) return;
```

with:

```java
            final CommandSpec<S> n = node;
            if (n.permission() != null && !gate(() -> adapter.hasPermission(sender, n.permission()), effDenied, sender, effError)) return;
            if (n.requirement() != null && !gate(() -> n.requirement().test(sender), n.onRequirementFail(), sender, effError)) return;
            if (n.playerOnly() && !gate(() -> adapter.isPlayer(sender), n.onPlayerOnlyFail(), sender, effError)) return;
            if (n.cooldownTracker() != null && !checkCooldown(n, sender, effError)) return;
```

Add a new private method right after `gate()` (i.e. right after the closing `}` of the `gate` method at line 94, before the `guard(...)` method):

```java
    /**
     * Fourth gate, cooldown-specific: unlike {@link #gate}, the check itself has a side effect
     * (it records a use), so it cannot reuse gate()'s BooleanSupplier shape — it needs its own
     * Duration-based decision. Mirrors gate()'s error handling (any Throwable routes to onError,
     * else is swallowed).
     */
    private boolean checkCooldown(CommandSpec<S> n, S sender, BiConsumer<S, Throwable> effError) {
        try {
            String key = adapter.identity(sender);
            if (key == null) return true; // no identity -> exempt

            Duration remaining = n.cooldownTracker().tryAcquire(key, n.cooldownDuration().get());
            if (remaining.isZero() || remaining.isNegative()) return true; // acquired

            guard(() -> n.onCooldown().accept(sender, remaining), effError, sender);
            return false;
        } catch (Throwable t) {
            guardThrowable(effError, sender, t);
            return false;
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=CommandDispatcherExecuteTest,CommandDispatcherCompleteTest test`
Expected: `Tests run: <all>, Failures: 0, Errors: 0`

Then run the full suite to confirm nothing else regressed:

Run: `mvn -q test`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hu/patriksgit/paxapi/command/CommandDispatcher.java \
        src/test/java/hu/patriksgit/paxapi/command/CommandDispatcherExecuteTest.java \
        src/test/java/hu/patriksgit/paxapi/command/CommandDispatcherCompleteTest.java
git commit -m "feat(command): wire CooldownTracker into CommandDispatcher.execute()"
```

---

## Task 5: Update `API.md`

**Files:**
- Modify: `API.md`

**Interfaces:**
- Consumes: nothing (docs only).
- Produces: nothing (terminal task).

- [ ] **Step 1: Correct the stale "no cooldown" claim and document the feature**

Modify `API.md` line 349 (inside "### Mit tud", under "## Command modul") — replace:

```
- Beépített: permission-check, requirement-predicate, player-only gate, alias-kezelés, tab-completion, hiba-routing (`onError`/`onDenied`/`onUnknown`)
```

with:

```
- Beépített: permission-check, requirement-predicate, player-only gate, per-key cooldown gate (`CooldownTracker`), alias-kezelés, tab-completion, hiba-routing (`onError`/`onDenied`/`onUnknown`)
```

Modify `API.md` line 354 (inside "### Mit NEM tud") — this line is now false and must be deleted entirely:

```
- Nincs beépített cooldown/rate-limiting hook
```

Modify `API.md` — in the "Builder-metódusok" bullet list (currently lines 393–402), add a new bullet right after the `.playerOnly(Consumer<S> onFail)` line (line 397):

```
- `.playerOnly(Consumer<S> onFail)`
```

becomes:

```
- `.playerOnly(Consumer<S> onFail)`
- `.cooldown(CooldownTracker tracker, Supplier<Duration> duration, BiConsumer<S,Duration> onCooldown)` — per-key (sender-identitás alapú) cooldown gate; csak `execute()`-ban fogyasztódik, `complete()`/tab-completion sosem consumálja; ld. lentebb
```

Modify `API.md` — insert a new subsection right after the "Builder-metódusok" bullet list and before `### CommandContext` (currently line 404):

```
### Cooldown gate (`.cooldown()` + `CooldownTracker`)

A `CooldownTracker` egy önálló, sender-identitás alapú, per-kulcs cooldown-nyilvántartó. Egy tracker egy parancshoz tartozik — te hozod létre és tartod életben (pl. egy plugin-mezőben), és te ütemezed rá az `evictOlderThan(...)`-t, mert a lib sosem indít saját szálat.

\`\`\`java
CooldownTracker reloadCooldown = new CooldownTracker();

CommandSpec<CommandSender> spec = CommandSpec.<CommandSender>root("mineauth")
    .sub("reload", ctx -> { /* ... */ })
    .group("setspawn", g -> g
        .cooldown(reloadCooldown, () -> Duration.ofSeconds(config.getInt("setspawn-cooldown-seconds", 5)),
            (sender, remaining) -> sender.sendMessage("Várj még " + remaining.toSeconds() + " másodpercet!"))
        .handler(ctx -> { /* ... */ }))
    .build();

// máshol, periodikusan (pl. a saját scheduleredből):
reloadCooldown.evictOlderThan(Duration.ofHours(1));
\`\`\`

- A kulcs a `SenderAdapter.identity(S)` — `PaperSenderAdapter`/`VelocitySenderAdapter` játékosnál a `UUID.toString()`-ot adja, egyébként (konzol, command block, RCON) `null`-t. **Null identitás = mentesülés a cooldown alól**, nem közös vödör.
- A cooldown **csak `CommandDispatcher.execute()`-ben fogyasztódik** — `complete()` (tab-completion) sosem érinti, még akkor sem, ha Velocity-n a `requires()`-hez hasonlóan off-thread fut.
- A gate-sorrend utolsó tagja: `permission` → `requirement` → `playerOnly` → `cooldown`. Egy megtagadott próbálkozás (bármelyik korábbi gate-en) sosem fogyasztja el a cooldownt.
- Egy blokkolt próbálkozás **nem** tolja el az ablakot — a cooldown mindig az utolsó ENGEDÉLYEZETT használattól számít.
- `duration` egy `Supplier<Duration>`, minden ellenőrzésnél frissen kiértékelve — configból élőben változtatható a hossz, a `CommandSpec`-fa újraépítése nélkül.
- `onCooldown` kötelező (`Objects.requireNonNull`), ugyanúgy mint a `requires()`/`playerOnly()` `onFail`-je.
- Ha a handler kivételt dob, a cooldown akkor is elhasználódik (gate-átengedéskor rögzít, a handler előtt).
```

(Use literal triple-backtick fences in the actual file, not the escaped `\`\`\`` shown above — escaped here only so this plan's own code fence doesn't terminate early.)

- [ ] **Step 2: Verify the doc renders sensibly**

Run: `git diff API.md`
Expected: the diff shows exactly the four changes above (one bullet edit, one bullet deletion, one bullet insertion, one new subsection) with no stray whitespace/fence damage.

- [ ] **Step 3: Commit**

```bash
git add API.md
git commit -m "docs(command): document the cooldown gate, remove stale 'no cooldown' claim"
```
