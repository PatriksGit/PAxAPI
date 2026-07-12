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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // A caller-supplied Duration Supplier that misbehaves (e.g. a config lookup returning null)
    // must fail loudly here, not deep inside Duration.plus(null) with a cryptic NPE — and
    // CommandDispatcher.checkCooldown wraps tryAcquire in a catch-all that silently swallows any
    // exception without an onError callback configured, so a clear message here is the only
    // thing standing between this failure and a silently "stuck" command.
    @Test void tryAcquireRejectsNullKey() {
        CooldownTracker tracker = new CooldownTracker();
        assertThrows(NullPointerException.class, () -> tracker.tryAcquire(null, Duration.ofSeconds(1)));
    }

    @Test void tryAcquireRejectsNullCooldown() {
        CooldownTracker tracker = new CooldownTracker();
        assertThrows(NullPointerException.class, () -> tracker.tryAcquire("k", null));
    }

    @Test void evictOlderThanRejectsNullAge() {
        CooldownTracker tracker = new CooldownTracker();
        assertThrows(NullPointerException.class, () -> tracker.evictOlderThan(null));
    }
}
