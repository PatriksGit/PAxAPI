package hu.patriksgit.paxapi.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HikariCP already retries opening a new connection whenever one is requested, but gives no
 * proactive signal that a DB which died mid-run has come back. watchConnection() pings on a
 * caller-supplied schedule and fires only on up/down TRANSITIONS, so a plugin can react to
 * recovery (e.g. re-enable a feature it disabled when the DB went down) without polling itself.
 */
class DatabaseWatchConnectionTest {

    private DataSource ds;
    private Connection conn;
    private Database db;
    private ScheduledExecutorService scheduler;

    private void wire() throws Exception {
        ds = mock(DataSource.class);
        conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        db = Database.forTesting(ds, false);
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    @Test void firesCallbackOnlyOnUpDownTransitions() throws Exception {
        wire();
        // tick1=up, tick2=up (no fire, still up), tick3=down (fire false), tick4=down (no fire,
        // still down), tick5+=up (fire true; Mockito repeats the last stub value afterward)
        when(conn.isValid(anyInt())).thenReturn(true, true, false, false, true);
        BlockingQueue<Boolean> events = new LinkedBlockingQueue<>();
        ScheduledFuture<?> handle = db.watchConnection(scheduler, 20, TimeUnit.MILLISECONDS, events::add);
        try {
            assertEquals(Boolean.FALSE, events.poll(2, TimeUnit.SECONDS), "must fire 'down' on the up->down transition");
            assertEquals(Boolean.TRUE, events.poll(2, TimeUnit.SECONDS), "must fire 'up' on the down->up transition");
        } finally {
            handle.cancel(true);
        }
    }

    @Test void doesNotFireWhenStatusNeverChanges() throws Exception {
        wire();
        when(conn.isValid(anyInt())).thenReturn(true);
        BlockingQueue<Boolean> events = new LinkedBlockingQueue<>();
        ScheduledFuture<?> handle = db.watchConnection(scheduler, 20, TimeUnit.MILLISECONDS, events::add);
        try {
            assertNull(events.poll(150, TimeUnit.MILLISECONDS), "steady 'up' state must never fire a callback");
        } finally {
            handle.cancel(true);
        }
    }

    @Test void throwingCallbackDoesNotKillTheScheduledTask() throws Exception {
        wire();
        // A ScheduledExecutorService silently stops all future runs of a periodic task if it
        // throws uncaught once — the watchdog must catch so one bad callback can't permanently
        // disable DB-recovery monitoring for the rest of the plugin's lifetime.
        when(conn.isValid(anyInt())).thenReturn(true, false, true, false, true);
        AtomicInteger calls = new AtomicInteger();
        ScheduledFuture<?> handle = db.watchConnection(scheduler, 20, TimeUnit.MILLISECONDS, up -> {
            calls.incrementAndGet();
            throw new RuntimeException("callback is broken");
        });
        try {
            long deadline = System.currentTimeMillis() + 2000;
            while (calls.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertTrue(calls.get() >= 3, "callback must keep being invoked on every transition despite throwing");
            assertFalse(handle.isDone(), "the scheduled task itself must survive the callback's exception");
        } finally {
            handle.cancel(true);
        }
    }

    @Test void requiresNonNullSchedulerAndCallback() throws Exception {
        wire();
        assertThrows(NullPointerException.class,
            () -> db.watchConnection(null, 1, TimeUnit.SECONDS, up -> {}));
        assertThrows(NullPointerException.class,
            () -> db.watchConnection(scheduler, 1, TimeUnit.SECONDS, null));
    }

    @Test void defaultIntervalOverloadUsesThirtySeconds() throws Exception {
        wire();
        when(conn.isValid(anyInt())).thenReturn(true);
        ScheduledFuture<?> handle = db.watchConnection(scheduler, up -> {});
        try {
            long delay = handle.getDelay(TimeUnit.SECONDS);
            assertTrue(delay > 0 && delay <= 30, "default overload must schedule at a 30s cadence; got initial delay=" + delay + "s");
        } finally {
            handle.cancel(true);
        }
    }
}
