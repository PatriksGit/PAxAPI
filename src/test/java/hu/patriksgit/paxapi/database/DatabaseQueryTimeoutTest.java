package hu.patriksgit.paxapi.database;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A stalled DB must not block the caller thread forever (on Paper/Velocity that thread is
 * often the main server thread). Every query helper must set a bounded statement-level
 * query timeout on every PreparedStatement it prepares, so a hung driver read is bounded
 * instead of blocking indefinitely.
 */
class DatabaseQueryTimeoutTest {

    private DataSource ds;
    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    private Database db;

    private void wire() throws Exception {
        ds = mock(DataSource.class);
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        db = Database.forTesting(ds, false);
    }

    @Test void querySetsAPositiveQueryTimeout() throws Exception {
        wire();
        db.query("SELECT 1", Sql.Binder.NONE, r -> null);
        verify(ps).setQueryTimeout(intThatIsPositive());
    }

    @Test void queryFirstSetsAPositiveQueryTimeout() throws Exception {
        wire();
        db.queryFirst("SELECT 1", Sql.Binder.NONE, r -> null);
        verify(ps).setQueryTimeout(intThatIsPositive());
    }

    @Test void updateSetsAPositiveQueryTimeout() throws Exception {
        wire();
        when(ps.executeUpdate()).thenReturn(0);
        db.update("UPDATE t SET x=1");
        verify(ps).setQueryTimeout(intThatIsPositive());
    }

    // ensureColumn/ensureColumnType/ensureIndex use a plain Statement (DDL identifiers can't be
    // bound as parameters), not a PreparedStatement — they were missed by the fix above and had
    // NO timeout at all. These typically run at plugin startup (schema migration) on the caller's
    // thread; an ALTER TABLE stuck on a metadata lock (an ordinary MySQL occurrence under load)
    // would block that thread forever without this.

    @Test void ensureColumnSetsAPositiveQueryTimeout() throws Exception {
        wire();
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        db.ensureColumn(conn, "players", "coins", "INT");
        verify(st).setQueryTimeout(intThatIsPositive());
    }

    @Test void ensureColumnTypeSetsAPositiveQueryTimeout() throws Exception {
        wire();
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        db.ensureColumnType(conn, "players", "coins", "BIGINT");
        verify(st).setQueryTimeout(intThatIsPositive());
    }

    @Test void ensureIndexSetsAPositiveQueryTimeout() throws Exception {
        wire();
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        db.ensureIndex(conn, "players", "idx_coins", "(coins)");
        verify(st).setQueryTimeout(intThatIsPositive());
    }

    private static int intThatIsPositive() {
        return org.mockito.ArgumentMatchers.intThat(v -> v > 0);
    }
}
