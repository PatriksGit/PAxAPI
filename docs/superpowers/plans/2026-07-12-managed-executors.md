# Managed Executors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `ManagedExecutors` utility class to PAxAPI (`hu.patriksgit.paxapi.concurrent`) that replaces the hand-written "named daemon thread pool + graceful shutdown" pattern currently duplicated four times across PAxAuth's Paper and Velocity modules.

**Architecture:** One new, dependency-free utility class with four static methods: three named-daemon-executor factories (`fixed`, `singleThread`, `singleThreadScheduled`) returning plain JDK `ExecutorService`/`ScheduledExecutorService` types (no wrapper type — callers still hand raw JDK types to interop points like `Database.watchConnection`), plus one graceful-shutdown helper (`shutdownGracefully`) that centralizes the `shutdown()` → `awaitTermination()` → `shutdownNow()` sequence, including interrupt handling and an optional forced-shutdown callback.

**Tech Stack:** Java 21, JUnit 5.10.2 (Jupiter), Maven. No new dependencies.

## Global Constraints

- Package: `hu.patriksgit.paxapi.concurrent` (new package).
- The library never spawns threads on its own initiative — every factory method only creates a pool/thread when the caller explicitly invokes it; no method starts background work implicitly.
- No automatic pool-size sizing heuristic in `fixed()` — `poolSize` is always caller-supplied and validated `> 0`.
- `onForcedShutdown` in `shutdownGracefully` is **nullable** (`null` = silent, matches the two `dbWatchdogScheduler` call sites in PAxAuth that never log on forced shutdown today).
- `shutdownGracefully` must never throw out of the method itself (except the two documented `NullPointerException`s for null `executor`/`timeout` arguments) — a throwing `onForcedShutdown` callback must be swallowed.
- All three factory methods validate their name/size arguments eagerly (`IllegalArgumentException`), not lazily inside the `ThreadFactory`.
- Spec: `docs/superpowers/specs/2026-07-12-managed-executors-design.md`.

---

### Task 1: `ManagedExecutors` — named daemon-executor factories

**Files:**
- Create: `src/main/java/hu/patriksgit/paxapi/concurrent/ManagedExecutors.java`
- Test: `src/test/java/hu/patriksgit/paxapi/concurrent/ManagedExecutorsTest.java`

**Interfaces:**
- Produces: `ManagedExecutors.fixed(String namePrefix, int poolSize) -> ExecutorService`,
  `ManagedExecutors.singleThread(String name) -> ExecutorService`,
  `ManagedExecutors.singleThreadScheduled(String name) -> ScheduledExecutorService`.
  Task 2 adds a fourth static method (`shutdownGracefully`) to this same file/class.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/hu/patriksgit/paxapi/concurrent/ManagedExecutorsTest.java`:

```java
package hu.patriksgit.paxapi.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedExecutorsTest {

    @Test void fixedCreatesPoolOfRequestedSizeWithDaemonNamedThreads() throws InterruptedException {
        int poolSize = 3;
        ExecutorService pool = ManagedExecutors.fixed("test-pool", poolSize);
        try {
            CountDownLatch ready = new CountDownLatch(poolSize);
            CountDownLatch release = new CountDownLatch(1);
            Thread[] captured = new Thread[poolSize];
            for (int i = 0; i < poolSize; i++) {
                int idx = i;
                pool.execute(() -> {
                    captured[idx] = Thread.currentThread();
                    ready.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release.countDown();

            for (Thread t : captured) {
                assertTrue(t.isDaemon(), "expected daemon thread, got " + t.getName());
                assertTrue(t.getName().matches("test-pool-\\d+"), "unexpected thread name: " + t.getName());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void fixedRejectsNonPositivePoolSize() {
        assertThrows(IllegalArgumentException.class, () -> ManagedExecutors.fixed("p", 0));
        assertThrows(IllegalArgumentException.class, () -> ManagedExecutors.fixed("p", -1));
    }

    @Test void fixedRejectsNullOrBlankNamePrefix() {
        assertThrows(IllegalArgumentException.class, () -> ManagedExecutors.fixed(null, 1));
        assertThrows(IllegalArgumentException.class, () -> ManagedExecutors.fixed("", 1));
        assertThrows(IllegalArgumentException.class, () -> ManagedExecutors.fixed("   ", 1));
    }

    @Test void singleThreadCreatesExactlyOneDaemonThreadNamedExactly() throws InterruptedException {
        ExecutorService exec = ManagedExecutors.singleThread("my-single");
        try {
            CountDownLatch done = new CountDownLatch(1);
            Thread[] captured = new Thread[1];
            exec.execute(() -> {
                captured[0] = Thread.currentThread();
                done.countDown();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals("my-single", captured[0].getName());
            assertTrue(captured[0].isDaemon());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test void singleThreadRejectsNullOrBlankName() {
        assertThrows(IllegalArgumentException.class, () -> ManagedExecutors.singleThread(null));
        assertThrows(IllegalArgumentException.class, () -> ManagedExecutors.singleThread(""));
        assertThrows(IllegalArgumentException.class, () -> ManagedExecutors.singleThread("   "));
    }

    @Test void singleThreadScheduledSupportsRepeatingSchedule() throws InterruptedException {
        ScheduledExecutorService scheduler = ManagedExecutors.singleThreadScheduled("my-scheduled");
        try {
            CountDownLatch ticks = new CountDownLatch(3);
            Thread[] captured = new Thread[1];
            scheduler.scheduleAtFixedRate(() -> {
                captured[0] = Thread.currentThread();
                ticks.countDown();
            }, 0, 10, TimeUnit.MILLISECONDS);

            assertTrue(ticks.await(5, TimeUnit.SECONDS), "expected at least 3 repeated executions");
            assertEquals("my-scheduled", captured[0].getName());
            assertTrue(captured[0].isDaemon());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test void singleThreadScheduledRejectsNullOrBlankName() {
        assertThrows(IllegalArgumentException.class, () -> ManagedExecutors.singleThreadScheduled(null));
        assertThrows(IllegalArgumentException.class, () -> ManagedExecutors.singleThreadScheduled(""));
        assertThrows(IllegalArgumentException.class, () -> ManagedExecutors.singleThreadScheduled("   "));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ManagedExecutorsTest test`
Expected: compile error — `ManagedExecutors` does not exist yet (or FAIL if a stub already exists).

- [ ] **Step 3: Write the implementation**

Create `src/main/java/hu/patriksgit/paxapi/concurrent/ManagedExecutors.java`:

```java
package hu.patriksgit.paxapi.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Named, daemon-thread executor construction + a shared graceful-shutdown helper. Replaces the
 * hand-written "named daemon pool + shutdown/awaitTermination/shutdownNow" pattern that would
 * otherwise be duplicated at every call site. The library never spawns threads on its own —
 * every method here only creates a pool/thread when the caller explicitly invokes it, and the
 * caller keeps holding a plain JDK {@link ExecutorService}/{@link ScheduledExecutorService}
 * (no wrapper type), so existing interop such as
 * {@link hu.patriksgit.paxapi.database.Database#watchConnection} is unaffected.
 */
public final class ManagedExecutors {
    private ManagedExecutors() {}

    /**
     * Fixed-size pool of daemon threads named {@code namePrefix + "-" + N} (N starting at 1).
     * No automatic sizing heuristic — {@code poolSize} must be a positive number the caller
     * chose based on their own workload (CPU-bound vs. I/O-bound).
     */
    public static ExecutorService fixed(String namePrefix, int poolSize) {
        requireNonBlank(namePrefix, "namePrefix");
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive, got " + poolSize);
        }
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, namePrefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(poolSize, tf);
    }

    /** Single daemon thread named exactly {@code name} (no sequence suffix). */
    public static ExecutorService singleThread(String name) {
        requireNonBlank(name, "name");
        return Executors.newSingleThreadExecutor(namedDaemonFactory(name));
    }

    /** Single daemon thread named exactly {@code name}, as a {@link ScheduledExecutorService}. */
    public static ScheduledExecutorService singleThreadScheduled(String name) {
        requireNonBlank(name, "name");
        return Executors.newSingleThreadScheduledExecutor(namedDaemonFactory(name));
    }

    private static ThreadFactory namedDaemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private static void requireNonBlank(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(paramName + " must not be null or blank, got '" + value + "'");
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=ManagedExecutorsTest test`
Expected: PASS, 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hu/patriksgit/paxapi/concurrent/ManagedExecutors.java src/test/java/hu/patriksgit/paxapi/concurrent/ManagedExecutorsTest.java
git commit -m "feat(concurrent): add ManagedExecutors named daemon-executor factories"
```

---

### Task 2: `ManagedExecutors.shutdownGracefully`

**Files:**
- Modify: `src/main/java/hu/patriksgit/paxapi/concurrent/ManagedExecutors.java`
- Modify: `src/test/java/hu/patriksgit/paxapi/concurrent/ManagedExecutorsTest.java`

**Interfaces:**
- Consumes: nothing from Task 1 beyond the existing class/file.
- Produces: `ManagedExecutors.shutdownGracefully(ExecutorService executor, Duration timeout, Runnable onForcedShutdown) -> void`.

- [ ] **Step 1: Write the failing tests**

Append these test methods inside the `ManagedExecutorsTest` class (before the final closing `}`), and add the imports listed right after:

```java
    @Test void drainsWithinTimeoutWithoutCallingForcedShutdown() throws InterruptedException {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch taskDone = new CountDownLatch(1);
        exec.execute(taskDone::countDown);
        assertTrue(taskDone.await(5, TimeUnit.SECONDS));

        AtomicBoolean forced = new AtomicBoolean(false);
        ManagedExecutors.shutdownGracefully(exec, Duration.ofSeconds(5), () -> forced.set(true));

        assertTrue(exec.isTerminated());
        assertFalse(forced.get());
    }

    @Test void callsForcedShutdownWhenTimeoutElapses() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch neverCounted = new CountDownLatch(1);
        exec.execute(() -> {
            try { neverCounted.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        AtomicInteger calls = new AtomicInteger();
        ManagedExecutors.shutdownGracefully(exec, Duration.ofMillis(50), calls::incrementAndGet);

        assertEquals(1, calls.get());
        assertTrue(exec.isShutdown());
    }

    @Test void nullOnForcedShutdownIsToleratedOnTimeoutBranch() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch neverCounted = new CountDownLatch(1);
        exec.execute(() -> {
            try { neverCounted.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        assertDoesNotThrow(() -> ManagedExecutors.shutdownGracefully(exec, Duration.ofMillis(50), null));
        assertTrue(exec.isShutdown());
    }

    @Test void throwingOnForcedShutdownIsSwallowed() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch neverCounted = new CountDownLatch(1);
        exec.execute(() -> {
            try { neverCounted.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        assertDoesNotThrow(() -> ManagedExecutors.shutdownGracefully(exec, Duration.ofMillis(50),
            () -> { throw new RuntimeException("boom"); }));
    }

    @Test void interruptedWhileAwaitingCallsForcedShutdownAndRestoresInterruptStatus() throws InterruptedException {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch neverCounted = new CountDownLatch(1);
        exec.execute(() -> {
            try { neverCounted.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean interruptedFlagSeen = new AtomicBoolean(false);
        CountDownLatch enteredAwait = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            enteredAwait.countDown();
            ManagedExecutors.shutdownGracefully(exec, Duration.ofSeconds(30), calls::incrementAndGet);
            interruptedFlagSeen.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        assertTrue(enteredAwait.await(5, TimeUnit.SECONDS));
        Thread.sleep(200); // let the worker actually reach the blocking awaitTermination call
        worker.interrupt();
        worker.join(5000);

        assertEquals(1, calls.get());
        assertTrue(interruptedFlagSeen.get(), "expected the worker thread's interrupt status to be restored");
    }

    @Test void nullOnForcedShutdownIsToleratedOnInterruptedBranch() throws InterruptedException {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch neverCounted = new CountDownLatch(1);
        exec.execute(() -> {
            try { neverCounted.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        CountDownLatch enteredAwait = new CountDownLatch(1);
        AtomicBoolean threw = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            enteredAwait.countDown();
            try {
                ManagedExecutors.shutdownGracefully(exec, Duration.ofSeconds(30), null);
            } catch (Throwable t) {
                threw.set(true);
            }
        });
        worker.start();
        assertTrue(enteredAwait.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        worker.interrupt();
        worker.join(5000);

        assertFalse(threw.get());
    }

    @Test void shutdownGracefullyRejectsNullExecutor() {
        assertThrows(NullPointerException.class,
            () -> ManagedExecutors.shutdownGracefully(null, Duration.ofSeconds(1), () -> {}));
    }

    @Test void shutdownGracefullyRejectsNullTimeout() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            assertThrows(NullPointerException.class,
                () -> ManagedExecutors.shutdownGracefully(exec, null, () -> {}));
        } finally {
            exec.shutdownNow();
        }
    }
```

Add these imports to the top of `ManagedExecutorsTest.java` (alongside the existing ones from Task 1):

```java
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
```

And these static imports:

```java
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ManagedExecutorsTest test`
Expected: compile error — `ManagedExecutors.shutdownGracefully` does not exist yet.

- [ ] **Step 3: Write the implementation**

Add these imports to `ManagedExecutors.java` (alongside the existing ones from Task 1):

```java
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
```

Add this method to the `ManagedExecutors` class (after `singleThreadScheduled`, before the private helpers):

```java
    /**
     * Drains {@code executor}: {@code shutdown()}, then waits up to {@code timeout} for it to
     * terminate. If it doesn't drain in time — OR if this thread is interrupted while waiting —
     * calls {@code shutdownNow()} and (if non-null) invokes {@code onForcedShutdown}. Restores
     * this thread's interrupt status if the wait was interrupted. Never throws out of this
     * method itself (beyond the null-check below): a throwing {@code onForcedShutdown} is
     * swallowed so it cannot block the rest of a caller's shutdown sequence.
     *
     * @param onForcedShutdown may be {@code null} — {@code null} means "stay silent on forced
     *                         shutdown", matching call sites that don't want any callback at all.
     */
    public static void shutdownGracefully(ExecutorService executor, Duration timeout, Runnable onForcedShutdown) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(timeout, "timeout");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                runForcedShutdownCallback(onForcedShutdown);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            runForcedShutdownCallback(onForcedShutdown);
        }
    }

    private static void runForcedShutdownCallback(Runnable onForcedShutdown) {
        if (onForcedShutdown == null) return;
        try { onForcedShutdown.run(); } catch (Throwable ignored) { }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=ManagedExecutorsTest test`
Expected: PASS, 15 tests green (7 from Task 1 + 8 from this task).

- [ ] **Step 5: Run the full test suite**

Run: `mvn -q test`
Expected: PASS, all tests green (no regressions elsewhere).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/hu/patriksgit/paxapi/concurrent/ManagedExecutors.java src/test/java/hu/patriksgit/paxapi/concurrent/ManagedExecutorsTest.java
git commit -m "feat(concurrent): add ManagedExecutors.shutdownGracefully"
```

---

### Task 3: Document `ManagedExecutors` in `API.md`

**Files:**
- Modify: `API.md`

**Interfaces:**
- Consumes: the final `ManagedExecutors` API from Tasks 1–2 (`fixed`, `singleThread`, `singleThreadScheduled`, `shutdownGracefully`).
- Produces: nothing consumed by later tasks (this is the last task in the plan).

- [ ] **Step 1: Update the module table and module count (top of `API.md`)**

Find this line (module count, near the top):
```
Package: `hu.patriksgit.paxapi`. Öt modulra oszlik:
```
Replace with:
```
Package: `hu.patriksgit.paxapi`. Hat modulra oszlik:
```

Find this table:
```
| Modul | Package | Mire jó |
|---|---|---|
| [Database](#database-modul) | `hu.patriksgit.paxapi.database` | MySQL + HikariCP kapcsolatkezelés, query helperek |
| [Config](#config-modul) | `hu.patriksgit.paxapi.config` | YAML config betöltés, típusos getterek |
| [Command](#command-modul) | `hu.patriksgit.paxapi.command` | Platform-független parancsfa (Paper + Velocity) |
| [Sound](#sound-modul) | `hu.patriksgit.paxapi.sound` | Egysoros hanglejátszás mindkét platformon |
| [Text](#text-modul) | `hu.patriksgit.paxapi.text` | Szín/formázás/placeholder/messages.yml kezelés |
```
Replace with:
```
| Modul | Package | Mire jó |
|---|---|---|
| [Database](#database-modul) | `hu.patriksgit.paxapi.database` | MySQL + HikariCP kapcsolatkezelés, query helperek |
| [Config](#config-modul) | `hu.patriksgit.paxapi.config` | YAML config betöltés, típusos getterek |
| [Command](#command-modul) | `hu.patriksgit.paxapi.command` | Platform-független parancsfa (Paper + Velocity) |
| [Concurrent](#concurrent-modul) | `hu.patriksgit.paxapi.concurrent` | Named daemon executor-ok + graceful shutdown helper |
| [Sound](#sound-modul) | `hu.patriksgit.paxapi.sound` | Egysoros hanglejátszás mindkét platformon |
| [Text](#text-modul) | `hu.patriksgit.paxapi.text` | Szín/formázás/placeholder/messages.yml kezelés |
```

- [ ] **Step 2: Update the table of contents**

Find:
```
1. [Database modul](#database-modul)
2. [Config modul](#config-modul)
3. [Command modul](#command-modul)
4. [Sound modul](#sound-modul)
5. [Text modul](#text-modul)
6. [Összefoglaló: mit NEM tud a PAxAPI](#összefoglaló-mit-nem-tud-a-paxapi)
```
Replace with:
```
1. [Database modul](#database-modul)
2. [Config modul](#config-modul)
3. [Command modul](#command-modul)
4. [Concurrent modul](#concurrent-modul)
5. [Sound modul](#sound-modul)
6. [Text modul](#text-modul)
7. [Összefoglaló: mit NEM tud a PAxAPI](#összefoglaló-mit-nem-tud-a-paxapi)
```

- [ ] **Step 3: Insert the new `## Concurrent modul` section**

Find the `### Platform-adapterek` section's end and the start of `## Sound modul` — i.e. find this exact line:
```
## Sound modul
```
Insert the following new section immediately BEFORE that line (so it lands between the end of the Command module and the start of the Sound module). Note the outer fence below uses four backticks specifically because the content itself contains a ```java fence — copy everything between the outer ```` markers, not including the ```` markers themselves:

````markdown
## Concurrent modul

`hu.patriksgit.paxapi.concurrent` — named daemon-thread executor konstrukció + egységes graceful-shutdown helper. Nem egy saját executor-wrapper típus: a hívó továbbra is sima `ExecutorService`/`ScheduledExecutorService`-t kap és tart (pl. hogy közvetlenül átadhassa a `Database.watchConnection(ScheduledExecutorService, ...)`-nek).

### Mit tud
- `ManagedExecutors.fixed(namePrefix, poolSize)` / `.singleThread(name)` / `.singleThreadScheduled(name)` — named, daemon szálú executor-ok, ugyanazzal a mintával, amit eddig minden hívó kézzel írt meg (`ThreadFactory` + daemon flag)
- `ManagedExecutors.shutdownGracefully(executor, timeout, onForcedShutdown)` — `shutdown()` → `awaitTermination(timeout)` → ha nem drainelt (vagy a várakozás megszakad), `shutdownNow()` + opcionális callback; sosem dob kifelé

### Mit NEM tud
- Nincs automatikus pool-mérethez sizing-heurisztika a `fixed()`-ben — a workload jellege (CPU-bound vs I/O-bound) a hívó döntése, a méretet mindig explicit kell megadni
- Nincs saját `ManagedExecutor` wrapper-típus — a nyers JDK típusokat kapod vissza, hogy a meglévő interop (pl. `watchConnection`) ne törjön
- Nincs thread-priority, rejection-policy vagy queue-capacity konfiguráció

### `ManagedExecutors` — factory-k + graceful shutdown

```java
ExecutorService authExecutor = ManagedExecutors.fixed("PAxAuth-Async", Math.max(2, Runtime.getRuntime().availableProcessors()));
ScheduledExecutorService dbWatchdog = ManagedExecutors.singleThreadScheduled("PAxAuth-DB-Watchdog");

// onDisable() / onShutdown()-ban:
ManagedExecutors.shutdownGracefully(authExecutor, Duration.ofMillis(500),
    () -> log.warn("Auth executor did not drain in time — forced shutdownNow."));
ManagedExecutors.shutdownGracefully(dbWatchdog, Duration.ofMillis(500), null); // néma, mint eddig
```

- `onForcedShutdown` **nullable** — `null` = néma `shutdownNow()`, nincs callback
- A callback mindkét ágon lefut: ha lejár a `timeout`, ÉS ha a várakozás közben megszakad (`InterruptedException`) — mindkettő "forced shutdown" esemény
- A megszakítás-kezelés (`Thread.currentThread().interrupt()` + `shutdownNow()`) a helperben él, nem kell a hívónak újraírnia
- `shutdownGracefully` sosem dob kifelé — egy dobó `onForcedShutdown` callback sem tudja megakasztani a többi cleanup-lépést egy `onDisable()`/`onShutdown()` láncban

````

- [ ] **Step 4: Fix the stale Command bullet and add a Concurrent bullet in the final summary section**

Find this exact line in the `## Összefoglaló: mit NEM tud a PAxAPI` section:
```
- **Command**: nincs cooldown/rate-limit hook; nincs async handler-execution támogatás; nincs strukturált argumentum-parser (csak `String[]`)
```
Replace with (this line is stale — the cooldown gate feature shipped in `v1.5.0` already removed this claim from the Command module's own `### Mit NEM tud` subsection, but this top-level summary line was never updated to match):
```
- **Command**: nincs async handler-execution támogatás; nincs strukturált argumentum-parser (csak `String[]`)
- **Concurrent**: nincs automatikus pool-mérethez sizing-heurisztika; nincs saját `ManagedExecutor` wrapper-típus; nincs thread-priority/rejection-policy/queue-capacity konfiguráció
```

- [ ] **Step 5: Verify the doc renders sanely**

Run: `grep -n "^## \|^### " API.md`
Expected: the heading list shows `## Concurrent modul` between `## Command modul`'s subsections and `## Sound modul`, and the TOC/table edits are present. (No automated test — this is a docs-only task; visually confirm headings are well-formed and no stray merge artifacts were left.)

- [ ] **Step 6: Commit**

```bash
git add API.md
git commit -m "docs(concurrent): document ManagedExecutors, fix stale Command cooldown claim"
```
