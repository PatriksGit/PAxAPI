package hu.patriksgit.paxapi.database;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Cross-consumer table-prefix consistency guard for a MySQL database shared by multiple
 * plugins (or multiple processes of the same plugin, e.g. Paper + Velocity). Each consumer
 * registers its configured {@link TablePrefix} in one shared, never-prefixed meta table
 * ({@value #META_TABLE}); a mismatch between what's registered and what's configured — a typo,
 * a half-applied config change — fails loudly at startup instead of silently splitting reads
 * and writes across two different table sets.
 *
 * <p>Call {@link #ensureConsistency} once per consumer, before running any migrations.
 */
public final class SchemaPrefixGuard {

    /** Shared meta table name. Never itself prefixed — every consumer's row lives here, keyed by component. */
    static final String META_TABLE = "paxapi_schema_meta";
    private static final String PREFIX_KEY = "table_prefix";
    // Same bound as Database's DDL/query helpers, and for the same reason: ensureConsistency
    // typically runs at startup on the caller's own thread (often a Minecraft main thread), and
    // the Connection it receives isn't guaranteed to come from a pool with its own socket
    // timeout — without this, a stalled/lock-contended query could block that thread forever.
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    private SchemaPrefixGuard() { }

    /**
     * Ensure {@code component}'s registered table-prefix (in the shared meta table) matches
     * {@code configuredPrefix}, registering it on first run.
     *
     * <p>Steps: create the meta table if missing; if no row exists yet for {@code component},
     * check {@code legacyUnprefixedTableNames} against the schema's actual tables (via JDBC
     * {@link DatabaseMetaData#getTables}) — if any already exist AND {@code configuredPrefix}
     * is non-empty, refuse (a fresh prefix on an existing legacy table set would silently orphan
     * live data behind a new, empty prefixed table set). Otherwise register the prefix
     * (idempotently, race-safe for concurrent first starts) and verify it reads back as
     * {@code configuredPrefix}.
     *
     * @param c                          an open connection; not closed by this method
     * @param component                  this consumer's identity, e.g. {@code "paxauth"}
     * @param configuredPrefix           this consumer's configured (already-{@link TablePrefix#validate}d) prefix
     * @param legacyUnprefixedTableNames table names this consumer historically created without a prefix;
     *                                   only the caller knows these — the guard has no plugin-specific knowledge
     * @throws SQLException if a legacy-table collision is detected, or the registered prefix
     *                       disagrees with {@code configuredPrefix}
     */
    public static void ensureConsistency(Connection c, String component, String configuredPrefix,
                                          Set<String> legacyUnprefixedTableNames) throws SQLException {
        Objects.requireNonNull(c, "connection");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(configuredPrefix, "configuredPrefix");
        Objects.requireNonNull(legacyUnprefixedTableNames, "legacyUnprefixedTableNames");
        // Iterate rather than legacyUnprefixedTableNames.contains(null): Set.of(...)'s immutable
        // Set implementations throw NPE from contains(null) itself, which would defeat this
        // exact check (turning it back into the undocumented NPE it's meant to prevent).
        for (String t : legacyUnprefixedTableNames) {
            if (t == null) {
                throw new IllegalArgumentException("legacyUnprefixedTableNames must not contain a null element");
            }
        }

        createMetaTableIfMissing(c);

        String registered = readPrefix(c, component);
        if (registered == null) {
            if (!configuredPrefix.isEmpty() && hasExistingLegacyTable(c, legacyUnprefixedTableNames)) {
                throw new SQLException("Component '" + component + "' is configured with table-prefix '"
                    + configuredPrefix + "' but un-prefixed legacy table(s) " + legacyUnprefixedTableNames
                    + " already exist in this schema. Refusing to start: this could silently orphan live "
                    + "data behind a new, empty prefixed table set. Check for a config typo, or migrate the "
                    + "legacy tables before enabling this prefix.");
            }
            insertPrefixIfAbsent(c, component, configuredPrefix);
            registered = readPrefix(c, component);
        }

        if (!Objects.equals(registered, configuredPrefix)) {
            throw new SQLException("Table-prefix mismatch for component '" + component + "': the shared "
                + "schema meta table has '" + registered + "' registered, but this instance is configured "
                + "with '" + configuredPrefix + "'. Refusing to start: a stale or mismatched prefix would "
                + "read/write the wrong table set. Align every consumer's configured table-prefix for this "
                + "database, or use a separate database if they're meant to diverge.");
        }
    }

    private static void createMetaTableIfMissing(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
            st.execute("CREATE TABLE IF NOT EXISTS " + META_TABLE + " ("
                + "component VARCHAR(64) NOT NULL, "
                + "meta_key VARCHAR(64) NOT NULL, "
                + "meta_value VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (component, meta_key)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private static String readPrefix(Connection c, String component) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT meta_value FROM " + META_TABLE + " WHERE component = ? AND meta_key = ?")) {
            ps.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
            ps.setString(1, component);
            ps.setString(2, PREFIX_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void insertPrefixIfAbsent(Connection c, String component, String configuredPrefix) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + META_TABLE + " (component, meta_key, meta_value) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE meta_value = meta_value")) {
            ps.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
            ps.setString(1, component);
            ps.setString(2, PREFIX_KEY);
            ps.setString(3, configuredPrefix);
            ps.executeUpdate();
        }
    }

    // Uses DatabaseMetaData.getTables (portable JDBC metadata) rather than a hand-written
    // information_schema query: MySQL treats "catalog" and "schema" as synonyms for the
    // database name, while other engines (e.g. H2, used by the integration test) keep them
    // distinct — passing both c.getCatalog() and c.getSchema() lets each driver apply its own
    // semantics. tableNamePattern is left null (not set to the legacy names) because JDBC search
    // patterns treat '_' as a single-char wildcard, which would wrongly match unrelated tables
    // whose name merely contains an underscore in that position.
    private static boolean hasExistingLegacyTable(Connection c, Set<String> legacyUnprefixedTableNames) throws SQLException {
        if (legacyUnprefixedTableNames.isEmpty()) return false;
        Set<String> existing = new HashSet<>();
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(c.getCatalog(), c.getSchema(), null, new String[]{"TABLE"})) {
            while (rs.next()) existing.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        for (String legacy : legacyUnprefixedTableNames) {
            if (existing.contains(legacy.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
