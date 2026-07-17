package hu.patriksgit.paxapi.database;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validation + name resolution for a per-consumer table-name prefix (e.g. {@code paxauth_},
 * {@code paxstaff_}) so multiple plugins can share one MySQL database without table-name
 * collisions.
 *
 * <p><b>Trust contract:</b> like {@link Database}'s DDL identifier guards, a prefix is
 * concatenated directly into SQL (table names cannot be JDBC-bound), so {@link #validate}
 * must run at bootstrap/construction time, before any SQL is built from the prefix — never
 * pass an unvalidated prefix to {@link #resolve}.
 */
public final class TablePrefix {

    // Same style as Database's SAFE_IDENT, but (unlike SAFE_IDENT) allows the empty string —
    // that's the backward-compatible default meaning "no prefix".
    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9_]*");

    /** MySQL's identifier length limit (table/column/index names), in characters. */
    private static final int MYSQL_IDENTIFIER_LIMIT = 64;

    private TablePrefix() { }

    /**
     * Trim and validate {@code rawPrefix}. Fails closed with {@link IllegalArgumentException}
     * rather than silently truncating or stripping bad characters.
     *
     * @param rawPrefix                   the configured prefix; {@code null} is treated as empty
     * @param longestBaseTableNameLength  length of the longest un-prefixed table name this
     *                                    consumer will create, so the combined name is checked
     *                                    against MySQL's identifier limit up front
     * @return the trimmed, validated prefix
     * @throws IllegalArgumentException if {@code longestBaseTableNameLength} is negative, if the
     *                                   prefix contains characters outside {@code [A-Za-z0-9_]},
     *                                   or if {@code prefix.length() + longestBaseTableNameLength}
     *                                   exceeds 64
     */
    public static String validate(String rawPrefix, int longestBaseTableNameLength) {
        if (longestBaseTableNameLength < 0) {
            throw new IllegalArgumentException(
                "longestBaseTableNameLength cannot be negative: " + longestBaseTableNameLength);
        }
        String trimmed = rawPrefix == null ? "" : rawPrefix.trim();
        if (!SAFE_PREFIX.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid table prefix '" + rawPrefix
                + "' — only letters, digits and underscore are allowed (empty is fine — that means no prefix).");
        }
        // long arithmetic: guards against int overflow at the (unrealistic but not impossible)
        // extreme end, now that a negative length can no longer offset it the other way.
        long combined = (long) trimmed.length() + longestBaseTableNameLength;
        if (combined > MYSQL_IDENTIFIER_LIMIT) {
            throw new IllegalArgumentException("Table prefix '" + trimmed + "' (" + trimmed.length()
                + " chars) plus the longest base table name (" + longestBaseTableNameLength
                + " chars) = " + combined + " chars, which exceeds MySQL's "
                + MYSQL_IDENTIFIER_LIMIT + "-character identifier limit.");
        }
        return trimmed;
    }

    /** Convenience wrapper around {@code prefix + baseTableName} so callers don't hand-concatenate. */
    public static String resolve(String prefix, String baseTableName) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(baseTableName, "baseTableName");
        return prefix + baseTableName;
    }
}
