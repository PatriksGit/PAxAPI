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
