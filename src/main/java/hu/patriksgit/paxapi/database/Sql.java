ackage hu.patriksgit.paxapi.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Functional interfaces used by {@link Database}'s query helpers. */
public final class Sql {
    private Sql() {}

    /** Binds parameters onto a prepared statement. */
    @FunctionalInterface public interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
        Binder NONE = ps -> { };
    }

    /** Maps the CURRENT row of a result set to a value. Never call {@code rs.next()}. */
    @FunctionalInterface public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** Binds parameters for one item of a batch. */
    @FunctionalInterface public interface BiBinder<T> {
        void bind(PreparedStatement ps, T item) throws SQLException;
    }

    /** A unit of work executed inside a transaction against the given connection. */
    @FunctionalInterface public interface TxBody<T> {
        T run(Connection c) throws SQLException;
    }
}
