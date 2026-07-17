package hu.patriksgit.paxapi.concurrent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test void oversizedTimeoutDoesNotThrowArithmeticException() throws InterruptedException {
        // Duration.toMillis() overflows (ArithmeticException) for a Duration this large; the
        // method must clamp it instead of crashing. Executor is already idle/terminated so the
        // (clamped) huge timeout doesn't actually make the test slow.
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

        AtomicBoolean forced = new AtomicBoolean(false);
        // Duration.ofSeconds (unlike ofDays) doesn't multiply its unit into the overflow range
        // at construction time, so this is the largest Duration constructible directly — and
        // still large enough that toMillis() alone would overflow without the production clamp.
        assertDoesNotThrow(() -> ManagedExecutors.shutdownGracefully(
            exec, Duration.ofSeconds(Long.MAX_VALUE), () -> forced.set(true)));
        assertFalse(forced.get());
    }

    @Test void hugeNegativeTimeoutDoesNotThrowArithmeticException() throws InterruptedException {
        // Symmetric case to the one above: Duration.toMillis() overflows at the negative extreme
        // too (e.g. Duration.ofSeconds(Long.MIN_VALUE)); the clamp must cover both directions.
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

        AtomicBoolean forced = new AtomicBoolean(false);
        assertDoesNotThrow(() -> ManagedExecutors.shutdownGracefully(
            exec, Duration.ofSeconds(Long.MIN_VALUE), () -> forced.set(true)));
        assertFalse(forced.get());
    }
}
