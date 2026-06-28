package hu.patriksgit.paxapi.database;

import org.junit.jupiter.api.*;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the query/batch/tx helpers against an in-memory H2 (MySQL mode).
 * Run with: mvn -Pit verify. We build a Database whose pool points at H2 by
 * using the dataSource()-bypassing constructor path: we can't reuse the MySQL
 * URL, so this test talks to H2 through a tiny subclass seam — see setUp.
 */
class DatabaseHelpersIT {

    // We bypass Database's MySQL pool and drive the helpers via a hand-made
    // Database backed by an H2 DataSource. Database's helpers only use ds.getConnection(),
    // so an H2-backed pool is sufficient. We expose a package-private test constructor.
    private Database db;

    @BeforeEach void setUp() throws Exception {
        org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:dbapi;MODE=MySQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        db = Database.forTesting(h2, false);
        try (Connection c = h2.getConnection(); Statement st = c.createStatement()) {
            // The named in-memory DB (DB_CLOSE_DELAY=-1) outlives db.close() — which is a
            // no-op on H2's non-AutoCloseable JdbcDataSource — so reset the table per test.
            st.execute("DROP TABLE IF EXISTS players");
            st.execute("CREATE TABLE players (uuid VARBINARY(16) PRIMARY KEY, name VARCHAR(16), pt BIGINT)");
        }
    }

    @AfterEach void tearDown() { db.close(); }

    @Test void nestedHelperInsideTxThrowsAndFlagClears() {
        // Calling another Database helper inside a tx body must fail fast (it would open a
        // separate pooled connection that doesn't join the tx and can self-deadlock).
        assertThrows(DataAccessException.class, () -> db.tx(c -> {
            db.update("INSERT INTO players (uuid,name,pt) VALUES (?,?,?)",
                ps -> { ps.setBytes(1, new byte[]{1}); ps.setString(2, "X"); ps.setLong(3, 1); });
            return null;
        }));
        // The IN_TX flag must be cleared afterward: a normal helper works again.
        int n = db.update("INSERT INTO players (uuid,name,pt) VALUES (?,?,?)",
            ps -> { ps.setBytes(1, new byte[]{2}); ps.setString(2, "Y"); ps.setLong(3, 1); });
        assertEquals(1, n);
    }

    @Test void crossInstanceHelperInsideTxIsAllowed() {
        // The IN_TX guard is per-instance: a tx on one Database may call a helper on a DIFFERENT
        // Database (separate pool) — that's safe and must NOT throw.
        Database other = Database.forTesting(db.dataSource(), false);
        int[] affected = new int[1];
        db.tx(c -> {
            affected[0] = other.update("INSERT INTO players (uuid,name,pt) VALUES (?,?,?)",
                ps -> { ps.setBytes(1, new byte[]{3}); ps.setString(2, "Z"); ps.setLong(3, 1); });
            return null;
        });
        assertEquals(1, affected[0]);
    }

    @Test void badParamIndexSurfacesAsDataAccessException() {
        // A binder using a 0/invalid index must yield a wrapped DataAccessException (driver's
        // clean SQLException), not a raw IndexOutOfBoundsException from the capturing wrapper.
        assertThrows(DataAccessException.class, () -> db.update(
            "INSERT INTO players (uuid,name,pt) VALUES (?,?,?)",
            ps -> ps.setString(0, "x")));
    }

    @Test void queryFirstToleratesNullMapperResult() {
        db.update("INSERT INTO players (uuid, name, pt) VALUES (?,?,?)",
            ps -> { ps.setBytes(1, new byte[]{1}); ps.setString(2, "Steve"); ps.setLong(3, 100); });
        // A mapper that legitimately returns null for the matched row must yield empty, not NPE.
        Optional<String> r = db.queryFirst("SELECT name FROM players WHERE uuid=?",
            ps -> ps.setBytes(1, new byte[]{1}), rs -> null);
        assertEquals(Optional.empty(), r);
    }

    @Test void updateThenQueryFirst() {
        int n = db.update("INSERT INTO players (uuid, name, pt) VALUES (?,?,?)",
            ps -> { ps.setBytes(1, new byte[]{1}); ps.setString(2, "Steve"); ps.setLong(3, 100); });
        assertEquals(1, n);

        Optional<String> name = db.queryFirst("SELECT name FROM players WHERE uuid=?",
            ps -> ps.setBytes(1, new byte[]{1}), rs -> rs.getString(1));
        assertEquals(Optional.of("Steve"), name);
    }

    @Test void queryReturnsAllRowsOrdered() {
        db.update("INSERT INTO players VALUES (?,?,?)", ps -> { ps.setBytes(1,new byte[]{1}); ps.setString(2,"A"); ps.setLong(3,5);});
        db.update("INSERT INTO players VALUES (?,?,?)", ps -> { ps.setBytes(1,new byte[]{2}); ps.setString(2,"B"); ps.setLong(3,9);});
        List<String> names = db.query("SELECT name FROM players ORDER BY pt DESC",
            rs -> rs.getString(1));
        assertEquals(List.of("B", "A"), names);
    }

    @Test void batchInsertsAll() {
        int[] counts = db.batch("INSERT INTO players (uuid,name,pt) VALUES (?,?,?)",
            List.of("X", "Y", "Z"),
            (ps, s) -> { ps.setBytes(1, s.getBytes()); ps.setString(2, s); ps.setLong(3, 1); });
        assertEquals(3, counts.length);
        Long total = db.queryFirst("SELECT COUNT(*) FROM players", rs -> rs.getLong(1)).orElse(0L);
        assertEquals(3L, total);
    }

    @Test void txCommits() {
        db.tx(c -> {
            try (var ps = c.prepareStatement("INSERT INTO players VALUES (?,?,?)")) {
                ps.setBytes(1, new byte[]{7}); ps.setString(2, "Tx"); ps.setLong(3, 1); ps.executeUpdate();
            }
            return null;
        });
        assertTrue(db.queryFirst("SELECT name FROM players WHERE uuid=?",
            ps -> ps.setBytes(1, new byte[]{7}), rs -> rs.getString(1)).isPresent());
    }

    @Test void txRollsBackOnException() {
        assertThrows(DataAccessException.class, () -> db.tx(c -> {
            try (var ps = c.prepareStatement("INSERT INTO players VALUES (?,?,?)")) {
                ps.setBytes(1, new byte[]{8}); ps.setString(2, "Rb"); ps.setLong(3, 1); ps.executeUpdate();
            }
            throw new java.sql.SQLException("boom"); // force rollback
        }));
        long n = db.queryFirst("SELECT COUNT(*) FROM players WHERE uuid=?",
            ps -> ps.setBytes(1, new byte[]{8}), rs -> rs.getLong(1)).orElse(0L);
        assertEquals(0L, n, "row must have been rolled back");
    }

    @Test void txRollsBackOnUncheckedException() {
        // Regression: a tx body that throws an unchecked exception (e.g. our own
        // DataAccessException, or any RuntimeException) must STILL roll back — a
        // SQLException-only catch would skip rollback and leak an open transaction.
        assertThrows(RuntimeException.class, () -> db.tx(c -> {
            try (var ps = c.prepareStatement("INSERT INTO players VALUES (?,?,?)")) {
                ps.setBytes(1, new byte[]{9}); ps.setString(2, "U"); ps.setLong(3, 1); ps.executeUpdate();
            }
            throw new IllegalStateException("unchecked boom");
        }));
        long n = db.queryFirst("SELECT COUNT(*) FROM players WHERE uuid=?",
            ps -> ps.setBytes(1, new byte[]{9}), rs -> rs.getLong(1)).orElse(0L);
        assertEquals(0L, n, "unchecked failure must also roll back");
    }

    @Test void batchIsAtomicAcrossFailure() {
        // A batch whose binder fails partway must leave NO rows committed
        // (all chunks share one transaction). Force a failure on the 2nd item.
        assertThrows(RuntimeException.class, () -> db.batch(
            "INSERT INTO players (uuid,name,pt) VALUES (?,?,?)",
            List.of("ok", "BAD", "also"),
            (ps, s) -> {
                if (s.equals("BAD")) throw new java.sql.SQLException("forced");
                ps.setBytes(1, s.getBytes()); ps.setString(2, s); ps.setLong(3, 1);
            }));
        long n = db.queryFirst("SELECT COUNT(*) FROM players", rs -> rs.getLong(1)).orElse(0L);
        assertEquals(0L, n, "no rows should be committed when the batch fails");
    }

    @Test void batchRollsBackOnUncheckedException() {
        // Regression: a batch binder that throws an UNCHECKED exception must still
        // roll back everything. Needs >1 chunk so the first chunk is already
        // executeBatch'd (rows pending) when a later chunk's binder throws. A
        // SQLException-only catch would skip rollback; finally's setAutoCommit(true)
        // would then COMMIT the pending first chunk — silent partial commit.
        int n = 105; // > BATCH_CHUNK (100): first chunk executes, second chunk fails
        java.util.List<Integer> items = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) items.add(i);
        assertThrows(RuntimeException.class, () -> db.batch(
            "INSERT INTO players (uuid,name,pt) VALUES (?,?,?)",
            items,
            (ps, i) -> {
                if (i == 100) throw new IllegalStateException("unchecked boom");
                ps.setBytes(1, new byte[]{(byte) (i >> 8), (byte) (i & 0xFF)});
                ps.setString(2, "n" + i);
                ps.setLong(3, 1);
            }));
        long c = db.queryFirst("SELECT COUNT(*) FROM players", rs -> rs.getLong(1)).orElse(0L);
        assertEquals(0L, c, "unchecked batch failure must roll back ALL chunks");
    }

    @Test void wrapsErrorWithSql() {
        DataAccessException e = assertThrows(DataAccessException.class,
            () -> db.update("INSERT INTO nope VALUES (1)"));
        assertTrue(e.getMessage().contains("nope"));
    }
}
