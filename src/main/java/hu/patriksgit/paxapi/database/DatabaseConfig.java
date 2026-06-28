ackage hu.patriksgit.paxapi.database;

import java.util.Locale;

/**
 * Immutable JDBC/pool configuration. Build it with {@link #builder()} — the single, fail-closed
 * construction entry point (a custom truststore and pool name are builder methods too).
 *
 * <p>{@code debugParams}: when true, {@link DataAccessException} messages include
 * actual bound parameter VALUES (handy for debugging, but may log secrets such as
 * password hashes / emails / IPs — values are control-stripped and length-capped).
 * Default false → only param types are logged.
 *
 * <p>Note: {@link #equals}/{@link #hashCode} are record-generated and therefore
 * include the password; {@link #toString()} is overridden to redact it. The canonical
 * constructor is low-level (all components positional) — use {@link #builder()} instead.
 */
public record DatabaseConfig(
    String host, int port, String database,
    String username, String password,
    int poolSize,
    long connectionTimeoutMs, long idleTimeoutMs, long maxLifetimeMs,
    SslMode sslMode,
    boolean debugParams,
    String trustStoreUrl, String trustStorePassword, String trustStoreType,
    String poolName
) {
    public DatabaseConfig {
        poolSize = Math.max(1, poolSize);
    }

    @Override public String toString() {
        return "DatabaseConfig[host=" + host + ", port=" + port + ", database=" + database
            + ", username=" + username + ", password=***, poolSize=" + poolSize
            + ", connectionTimeoutMs=" + connectionTimeoutMs + ", idleTimeoutMs=" + idleTimeoutMs
            + ", maxLifetimeMs=" + maxLifetimeMs + ", sslMode=" + sslMode
            + ", debugParams=" + debugParams
            + ", trustStoreUrl=" + trustStoreUrl
            + ", trustStorePassword=" + (trustStorePassword == null ? "null" : "***")
            + ", trustStoreType=" + trustStoreType
            + ", poolName=" + poolName + "]";
    }

    /** Start a fluent builder. Required: host, database, username, password, sslMode. */
    public static Builder builder() { return new Builder(); }

    /**
     * Fluent builder for {@link DatabaseConfig}. New config options can be added here as one
     * method without breaking callers. {@link #build()} fails closed if a required field is unset.
     */
    public static final class Builder {
        private String host, database, username, password;
        private SslMode sslMode;
        private int port = 3306;
        private int poolSize = 10;
        private long connectionTimeoutMs = 0L, idleTimeoutMs = 0L, maxLifetimeMs = 0L;
        private boolean debugParams = false;
        private String trustStoreUrl, trustStorePassword, trustStoreType, poolName;

        public Builder host(String v) { this.host = v; return this; }
        public Builder port(int v) { this.port = v; return this; }
        public Builder database(String v) { this.database = v; return this; }
        public Builder username(String v) { this.username = v; return this; }
        public Builder password(String v) { this.password = v; return this; }
        public Builder poolSize(int v) { this.poolSize = v; return this; }
        public Builder connectionTimeoutMs(long v) { this.connectionTimeoutMs = v; return this; }
        public Builder idleTimeoutMs(long v) { this.idleTimeoutMs = v; return this; }
        public Builder maxLifetimeMs(long v) { this.maxLifetimeMs = v; return this; }
        public Builder sslMode(SslMode v) { this.sslMode = v; return this; }
        public Builder debugParams(boolean v) { this.debugParams = v; return this; }
        public Builder trustStore(String url, String storePassword, String type) {
            this.trustStoreUrl = url; this.trustStorePassword = storePassword; this.trustStoreType = type; return this;
        }
        public Builder poolName(String v) { this.poolName = v; return this; }

        public DatabaseConfig build() {
            java.util.Objects.requireNonNull(host, "host");
            java.util.Objects.requireNonNull(database, "database");
            java.util.Objects.requireNonNull(username, "username");
            java.util.Objects.requireNonNull(password, "password");
            java.util.Objects.requireNonNull(sslMode, "sslMode");
            return new DatabaseConfig(host, port, database, username, password, poolSize,
                connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs, sslMode, debugParams,
                trustStoreUrl, trustStorePassword, trustStoreType, poolName);
        }
    }

    /**
     * TLS posture for the JDBC connection. Maps directly to Connector/J's
     * {@code sslMode} URL parameter.
     */
    public enum SslMode {
        /** No TLS. Acceptable for localhost-only deployments. */
        DISABLED,
        /** TLS encrypted, server certificate NOT verified. Vulnerable to MITM. */
        REQUIRED,
        /** TLS encrypted, CA chain verified. Recommended for remote DBs. */
        VERIFY_CA,
        /** TLS encrypted, CA chain + hostname verified. Strongest. */
        VERIFY_IDENTITY;

        /**
         * Parse a config string. Case-insensitive; '-' normalized to '_'; accepts
         * the legacy boolean {@code ssl: true/false} mapping. Fails closed: any
         * unrecognized input throws rather than silently downgrading TLS.
         *
         * @throws IllegalArgumentException if {@code raw} is null, blank, or unknown.
         */
        public static SslMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("ssl-mode is blank or missing");
            }
            String t = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            return switch (t) {
                case "DISABLED", "FALSE", "NO", "OFF" -> DISABLED;
                case "REQUIRED" -> REQUIRED;
                case "VERIFY_CA", "TRUE", "YES", "ON" -> VERIFY_CA;
                case "VERIFY_IDENTITY" -> VERIFY_IDENTITY;
                default -> throw new IllegalArgumentException(
                    "Unknown ssl-mode '" + raw + "'. Valid: DISABLED, REQUIRED, VERIFY_CA, "
                    + "VERIFY_IDENTITY (or legacy boolean: true/false/yes/no/on/off).");
            };
        }
    }
}
