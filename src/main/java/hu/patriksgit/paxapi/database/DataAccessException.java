package hu.patriksgit.paxapi.database;

import java.sql.SQLException;
import java.util.List;
import java.util.StringJoiner;

/**
 * Unchecked wrapper for a {@link SQLException} from a {@link Database} helper.
 * The message embeds the SQL plus parameter info so a single log line tells you
 * what failed and which query caused it. Parameter VALUES appear only when the
 * config has {@code debugParams=true} (otherwise just their types, to avoid
 * leaking secrets like password hashes / emails / IPs).
 *
 * <p>To keep the message a single, safe log line, the SQL and any debug values are
 * control-stripped (newlines/tabs/control chars → spaces) and debug values are
 * length-capped — this prevents log-injection via attacker-controlled parameters
 * and avoids dumping huge blobs.
 *
 * <p><b>Logging note:</b> {@link #getMessage()} is sanitized — the driver's own message
 * (which a MySQL {@code SQLException} can inline row values into, e.g.
 * {@code Duplicate entry 'a@b.com' for key ...}) is only included when {@code debugParams}
 * is on, same as bound parameter values. The attached {@link #getCause()} object itself is
 * NOT sanitized, so logging the raw throwable or its full stack trace bypasses this — to
 * keep logs PII-safe, log {@code ex.getMessage()} rather than the throwable.
 */
public final class DataAccessException extends RuntimeException {

    /** Max rendered length of a single bound value in debug mode. */
    private static final int MAX_DEBUG_VALUE_LEN = 64;

    private DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Wrap a non-SQL failure from inside a {@code tx}/{@code batch} body (preserves the cause). */
    static DataAccessException fromBody(String message, Throwable cause) {
        return new DataAccessException(stripControl(message), cause);
    }

    static DataAccessException wrap(String sql, List<?> params, boolean debugParams, SQLException cause) {
        StringBuilder sb = new StringBuilder("SQL failed: ").append(stripControl(sql));
        if (params != null && !params.isEmpty()) {
            StringJoiner j = new StringJoiner(", ", " params=[", "]");
            for (Object p : params) j.add(describe(p, debugParams));
            sb.append(j);
        }
        if (cause != null) {
            sb.append(" (").append(cause.getClass().getSimpleName());
            // The driver's own message can inline actual row data (e.g. MySQL 1062 "Duplicate
            // entry 'user@example.com' for key ...") independent of whether any bound PARAMETER
            // is being debug-printed — so it must be gated behind the same debugParams flag,
            // not included unconditionally. Without this, "log ex.getMessage()" (the PII-safe
            // practice this class's own Javadoc recommends) would still leak PII when debug is off.
            if (debugParams) {
                sb.append(": ").append(stripControl(cause.getMessage()));
            }
            sb.append(", SQLState=").append(cause.getSQLState())
              .append(", code=").append(cause.getErrorCode()).append(')');
        }
        return new DataAccessException(sb.toString(), cause);
    }

    private static String describe(Object p, boolean debugParams) {
        if (p == null) return "null";
        // byte[] (UUID/BINARY) always prints as byte[N] (content-free) in BOTH modes — never
        // dump raw bytes nor the useless array identity hash from String.valueOf(byte[]).
        if (p instanceof byte[] b) return "byte[" + b.length + "]";
        if (debugParams) return truncate(stripControl(String.valueOf(p)));
        return p.getClass().getSimpleName();
    }

    /**
     * Replace control characters (incl. newline/tab, DEL) and the Unicode line separators
     * NEL (U+0085), LS (U+2028), PS (U+2029) with spaces so the message stays one log line.
     */
    private static String stripControl(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u0000-\\u001F\\u007F\\u0085\\u2028\\u2029]", " ");
    }

    /** Cap a rendered value; if over the limit, keep the head and append the original length. */
    private static String truncate(String s) {
        if (s.length() <= MAX_DEBUG_VALUE_LEN) return s;
        return s.substring(0, MAX_DEBUG_VALUE_LEN) + "…(" + s.length() + ")";
    }
}
