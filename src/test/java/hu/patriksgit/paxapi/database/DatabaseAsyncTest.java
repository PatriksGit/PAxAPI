package hu.patriksgit.paxapi.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The class Javadoc promises "the library never spawns threads" — the async helpers must
 * honor that by requiring a caller-supplied {@link java.util.concurrent.Executor} rather than
 * creating an internal pool. These tests verify the work actually runs on that executor (not
 * the calling thread) and that failures surface through the returned {@link CompletableFuture}
 * the normal way (wrapped in {@link ExecutionException}), not as an immediate throw.
 */
class DatabaseAsyncTest {

    private DataSource ds;
    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    private Database db;
    private ExecutorService executor;

    private void wire() throws Exception {
        ds = mock(DataSource.class);
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        db = Database.forTesting(ds, false);
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach void shutdown() {
        if (executor != null) executor.shutdownNow();
    }

    @Test void queryAsyncRunsOnTheGivenExecutorAndReturnsTheSameResultAsSync() throws Exception {
        wire();
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("Steve");
        Thread callingThread = Thread.currentThread();
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        List<String> result = db.queryAsync("SELECT name", Sql.Binder.NONE,
                r -> { ranOn.set(Thread.currentThread()); return r.getString(1); }, executor)
            .get(2, TimeUnit.SECONDS);

        assertEquals(List.of("Steve"), result);
        assertNotEquals(callingThread, ranOn.get(), "row mapping must run on the supplied executor, not the caller thread");
    }

    @Test void queryAsyncFailurePropagatesAsDataAccessExceptionThroughTheFuture() throws Exception {
        wire();
        when(ps.executeQuery()).thenThrow(new SQLException("boom"));

        CompletableFuture<List<Object>> future = db.queryAsync("SELECT 1", Sql.Binder.NONE, r -> null, executor);
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertInstanceOf(DataAccessException.class, ex.getCause());
    }

    @Test void queryAsyncRejectsNullExecutor() throws Exception {
        wire();
        assertThrows(NullPointerException.class, () -> db.queryAsync("SELECT 1", Sql.Binder.NONE, r -> null, null));
    }

    @Test void queryFirstAsyncReturnsSameResultAsSync() throws Exception {
        wire();
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("Steve");

        var result = db.queryFirstAsync("SELECT name", Sql.Binder.NONE, r -> r.getString(1), executor)
            .get(2, TimeUnit.SECONDS);

        assertEquals(java.util.Optional.of("Steve"), result);
    }

    @Test void updateAsyncReturnsAffectedRows() throws Exception {
        wire();
        when(ps.executeUpdate()).thenReturn(3);

        int n = db.updateAsync("UPDATE t SET x=1", Sql.Binder.NONE, executor).get(2, TimeUnit.SECONDS);

        assertEquals(3, n);
    }

    @Test void txAsyncCommitsAndReturnsBodyResult() throws Exception {
        wire();

        String result = db.txAsync(c -> "ok", executor).get(2, TimeUnit.SECONDS);

        assertEquals("ok", result);
        verify(conn).commit();
    }

    @Test void batchAsyncReturnsPerItemAffectedCounts() throws Exception {
        wire();
        when(ps.executeBatch()).thenReturn(new int[]{1, 1});

        int[] result = db.batchAsync("INSERT INTO t VALUES (?)", List.of("a", "b"),
                (statement, item) -> statement.setString(1, item), executor)
            .get(2, TimeUnit.SECONDS);

        assertArrayEquals(new int[]{1, 1}, result);
    }

    @Test void pingReturnsTrueWhenConnectionIsValid() throws Exception {
        wire();
        when(conn.isValid(anyInt())).thenReturn(true);
        assertTrue(db.ping());
    }

    @Test void pingReturnsFalseWhenConnectionIsInvalid() throws Exception {
        wire();
        when(conn.isValid(anyInt())).thenReturn(false);
        assertFalse(db.ping());
    }

    @Test void pingReturnsFalseWhenGetConnectionThrowsInsteadOfPropagating() throws Exception {
        ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection refused"));
        db = Database.forTesting(ds, false);
        assertFalse(db.ping());
    }

    // The tx-nesting guard (checkNotInTx / the per-instance ThreadLocal `inTx`) exists to
    // prevent a helper call from silently opening a SECOND pooled connection that doesn't join
    // the enclosing transaction — but the guard's ThreadLocal is only ever set on the thread
    // that called tx()/batch(). If an *Async method schedules the real work onto a different
    // (executor) thread before checking, the check runs on a thread where the flag was never
    // set, and the guard is silently bypassed — reopening exactly the non-atomicity/deadlock
    // risk checkNotInTx() exists to prevent. The fix must check synchronously, on the CALLER's
    // thread, before handing off to the executor — so these must throw immediately, not via
    // the returned future.

    @Test void queryAsyncCalledInsideTxThrowsSynchronouslyInsteadOfEscapingTheGuard() throws Exception {
        wire();
        assertThrows(DataAccessException.class, () -> db.tx(c -> {
            db.queryAsync("SELECT 1", Sql.Binder.NONE, r -> null, executor);
            return null;
        }));
    }

    @Test void queryFirstAsyncCalledInsideTxThrowsSynchronously() throws Exception {
        wire();
        assertThrows(DataAccessException.class, () -> db.tx(c -> {
            db.queryFirstAsync("SELECT 1", Sql.Binder.NONE, r -> null, executor);
            return null;
        }));
    }

    @Test void updateAsyncCalledInsideTxThrowsSynchronously() throws Exception {
        wire();
        assertThrows(DataAccessException.class, () -> db.tx(c -> {
            db.updateAsync("UPDATE t SET x=1", executor);
            return null;
        }));
    }

    @Test void txAsyncCalledInsideTxThrowsSynchronously() throws Exception {
        wire();
        assertThrows(DataAccessException.class, () -> db.tx(c -> {
            db.txAsync(inner -> "nested", executor);
            return null;
        }));
    }

    @Test void batchAsyncCalledInsideTxThrowsSynchronously() throws Exception {
        wire();
        assertThrows(DataAccessException.class, () -> db.tx(c -> {
            db.batchAsync("INSERT INTO t VALUES (?)", List.of("a"), (statement, item) -> {}, executor);
            return null;
        }));
    }

    // Sanity: the guard must not falsely trigger for async calls made OUTSIDE any transaction
    // (already covered by the happy-path tests above, but explicit here for the fix's own sake).
    @Test void asyncHelpersOutsideAnyTxAreUnaffectedByTheGuard() throws Exception {
        wire();
        when(ps.executeUpdate()).thenReturn(1);
        assertDoesNotThrow(() -> db.updateAsync("UPDATE t SET x=1", executor).get(2, TimeUnit.SECONDS));
    }

    // ping()/pingAsync() also open their own pooled connection via ds.getConnection() — the
    // exact same nested-connection risk query/update/etc. are guarded against. This was missed
    // when ping/pingAsync were first added; CodeRabbit caught it on review.
    @Test void pingCalledInsideTxThrowsSynchronously() throws Exception {
        wire();
        assertThrows(DataAccessException.class, () -> db.tx(c -> {
            db.ping();
            return null;
        }));
    }

    @Test void pingAsyncCalledInsideTxThrowsSynchronously() throws Exception {
        wire();
        assertThrows(DataAccessException.class, () -> db.tx(c -> {
            db.pingAsync(executor);
            return null;
        }));
    }

    @Test void pingAsyncIsQueuedOnTheExecutorRatherThanRunInline() throws Exception {
        wire();
        when(conn.isValid(anyInt())).thenReturn(true);
        java.util.concurrent.CountDownLatch blockExecutor = new java.util.concurrent.CountDownLatch(1);
        executor.submit(() -> {
            try { blockExecutor.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        });

        CompletableFuture<Boolean> future = db.pingAsync(executor);
        assertFalse(future.isDone(), "must be queued behind the executor's busy task, not run on the caller thread");

        blockExecutor.countDown();
        assertTrue(future.get(2, TimeUnit.SECONDS));
    }
}
