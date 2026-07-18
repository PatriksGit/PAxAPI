package hu.patriksgit.paxapi.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Hardened MySQL connection pool + easy query helpers. Platform-independent
 * (pure JDBC/HikariCP/slf4j). Construct once at startup, {@link #close()} on
 * shutdown. Helpers run on the CALLER's thread — the library never spawns threads.
 *
 * <p>The {@code *Async} variants ({@link #queryAsync}, {@link #queryFirstAsync},
 * {@link #updateAsync}, {@link #txAsync}, {@link #batchAsync}, {@link #pingAsync}) keep that
 * same promise: each requires an explicit caller-supplied {@link java.util.concurrent.Executor}
 * (e.g. the plugin's own async scheduler) rather than the library creating its own thread pool.
 * They are plain {@code CompletableFuture.supplyAsync} wrappers around the sync helpers, so
 * failures surface as a wrapped {@code DataAccessException} via the future, not an immediate throw.
 *
 * <p>{@link #watchConnection} is a periodic health-check watchdog for a DB that dies mid-run
 * (not just at startup) — it also requires a caller-supplied {@code ScheduledExecutorService}.
 */
public final class Database implements AutoCloseable {

    // Host: hostname / IPv4 / [IPv6]. DB name: strict identifier. These guard the
    // URL-concatenated fields so a value like `db?sslMode=DISABLED` can't break out
    // of its segment and silently downgrade TLS.
    private static final Pattern SAFE_DB_HOST =
        Pattern.compile("[A-Za-z0-9._\\-]+|\\[[0-9A-Fa-f:]+\\]");
    private static final Pattern SAFE_DB_NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_COLUMN_EXPR = Pattern.compile("[A-Za-z0-9_(),.'\\-\\s]+");
    // JMX-safe pool name: alnum, dot, underscore, hyphen, space. Excludes : , = * ? " and control chars
    // so the name can't break the HikariCP JMX ObjectName.
    private static final Pattern SAFE_POOL_NAME = Pattern.compile("[A-Za-z0-9._\\- ]+");
    /** Default batch chunk size — keeps SQL shapes stable and stays under driver limits. */
    private static final int BATCH_CHUNK = 100;
    // A Minecraft server's main thread must never block on I/O for an unbounded time. Every
    // helper here documents "runs on the caller's thread" — without a bound, a stalled DB
    // (network partition, lock wait, overloaded server) freezes whichever thread called in,
    // which is very often the main thread. socketTimeout/connectTimeout bound the driver's
    // blocking socket reads; setQueryTimeout (statement-level, seconds) bounds query execution.
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    // Typed as DataSource (not HikariDataSource) so the test seam can back it with H2.
    // The production constructor assigns a Hikari pool.
    private final DataSource ds;
    private final boolean debugParams;

    // True while THIS instance is running a tx()/batch() body on the current thread. Per-instance
    // (not static) so a plugin holding two Databases can safely wrap one's tx around the other's
    // helper. Within the SAME instance, the other helpers open their own pooled connection (they
    // don't join the transaction) — calling them inside a body risks silent non-atomicity and a
    // self-deadlock at small pool sizes, so we fail fast.
    private final ThreadLocal<Boolean> inTx = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public Database(DatabaseConfig cfg, Logger log) {
        this(cfg, log, null);
    }

    /**
     * As {@link #Database(DatabaseConfig, Logger)}, plus an optional escape hatch to set any
     * HikariConfig property the library does not model. The {@code customizer} runs FIRST (before
     * the JDBC URL and driver class are set — so reading {@code hc.getJdbcUrl()} inside it yields
     * null); the library then re-asserts a SPECIFIC set of protections after it: the jdbcUrl
     * (carrying {@code sslMode} + {@code useAffectedRows}), the driver class, the four blast-radius
     * flags, and the configured truststore. Those enumerated settings cannot be overridden by the
     * customizer. This is a targeted re-assertion, NOT a sandbox: the customizer can still set
     * other driver properties (e.g. a truststore when none is configured), so treat it as trusted
     * developer code, not a place to forward semi-trusted config.
     */
    public Database(DatabaseConfig cfg, Logger log, Consumer<HikariConfig> customizer) {
        this.debugParams = cfg.debugParams();

        if (cfg.host() == null || !SAFE_DB_HOST.matcher(cfg.host()).matches()) {
            throw new IllegalArgumentException("Invalid database host '" + cfg.host()
                + "' — must be hostname / IPv4 / [IPv6], no URL/query characters.");
        }
        if (cfg.port() < 1 || cfg.port() > 65535) {
            throw new IllegalArgumentException("Invalid database port '" + cfg.port() + "'.");
        }
        if (cfg.database() == null || !SAFE_DB_NAME.matcher(cfg.database()).matches()) {
            throw new IllegalArgumentException("Invalid database name '" + cfg.database()
                + "' — must be [A-Za-z0-9_]+; no JDBC URL parameter chars allowed.");
        }
        if (cfg.trustStoreUrl() != null && !cfg.trustStoreUrl().isBlank()) {
            String tsLower = cfg.trustStoreUrl().trim().toLowerCase(java.util.Locale.ROOT);
            // Allowlist file:, not a denylist of remote schemes — a denylist of just http(s)
            // still lets other URL-Handler-backed remote schemes (jar:http:, ftp:) fetch the
            // truststore over the network, defeating the MITM protection this guard exists for.
            if (!tsLower.startsWith("file:")) {
                throw new IllegalArgumentException("Refusing non-local trustStoreUrl '" + cfg.trustStoreUrl()
                    + "': a remotely fetched truststore enables MITM. Use a local file: URL.");
            }
        }

        boolean loopback = "localhost".equalsIgnoreCase(cfg.host())
            || "127.0.0.1".equals(cfg.host()) || "::1".equals(cfg.host()) || "[::1]".equals(cfg.host());
        switch (cfg.sslMode()) {
            case DISABLED -> {
                if (loopback) log.info("ssl-mode=DISABLED on loopback ({}) — TLS skipped (acceptable for localhost).", cfg.host());
                else log.warn("ssl-mode=DISABLED but host '{}' is not loopback — DB credentials and traffic travel UNENCRYPTED. "
                    + "Use VERIFY_CA (or VERIFY_IDENTITY) for any remote MySQL.", cfg.host());
            }
            case REQUIRED -> log.warn("ssl-mode=REQUIRED — TLS is on but the server certificate is NOT verified. "
                + "A MITM with a self-signed cert can intercept credentials. Prefer VERIFY_CA or VERIFY_IDENTITY.");
            case VERIFY_CA, VERIFY_IDENTITY -> {
                if (loopback) log.warn("ssl-mode={} on loopback ('{}') — the default MySQL self-signed cert will likely "
                    + "FAIL the CA-chain check. For local dev set ssl-mode=DISABLED; for production-on-localhost provision a CA-signed cert.",
                    cfg.sslMode(), cfg.host());
            }
        }

        String sslParam = switch (cfg.sslMode()) {
            case DISABLED -> "sslMode=DISABLED";
            case REQUIRED -> "sslMode=REQUIRED";
            case VERIFY_CA -> "sslMode=VERIFY_CA";
            case VERIFY_IDENTITY -> "sslMode=VERIFY_IDENTITY";
        };
        // useAffectedRows=false (driver default, pinned): callers relying on the MySQL
        // "INSERT...ON DUPLICATE KEY UPDATE returns 1 for insert / 2 for update" convention
        // (e.g. MineAuth's brute-force counter) need this.
        String jdbcUrl = "jdbc:mysql://" + cfg.host() + ":" + cfg.port() + "/" + cfg.database()
            + "?" + sslParam + "&serverTimezone=UTC&useUnicode=true&characterEncoding=utf8&useAffectedRows=false";

        // Unique-per-database pool name by default so logs/JMX distinguish each plugin's pool;
        // an explicit cfg.poolName() overrides it (validated JMX-safe).
        String poolName;
        if (cfg.poolName() != null && !cfg.poolName().isBlank()) {
            if (!SAFE_POOL_NAME.matcher(cfg.poolName()).matches()) {
                throw new IllegalArgumentException("Invalid poolName '" + cfg.poolName()
                    + "' — allowed: letters, digits, '.', '_', '-', space (JMX-safe).");
            }
            poolName = cfg.poolName();
        } else {
            poolName = "PAxAPI-DB-" + cfg.database();
        }

        HikariConfig hc = new HikariConfig();
        // --- non-critical settings: the customizer may freely override these ---
        hc.setUsername(cfg.username());
        hc.setPassword(cfg.password());
        hc.setMaximumPoolSize(cfg.poolSize());
        hc.setPoolName(poolName);
        if (cfg.connectionTimeoutMs() > 0) hc.setConnectionTimeout(cfg.connectionTimeoutMs());
        if (cfg.idleTimeoutMs() > 0) hc.setIdleTimeout(cfg.idleTimeoutMs());
        if (cfg.maxLifetimeMs() > 0) hc.setMaxLifetime(cfg.maxLifetimeMs());
        // Lazy pool: don't open a connection in the constructor — failures surface on first
        // getConnection(), and unit tests can construct a Database without a live DB.
        hc.setInitializationFailTimeout(-1);
        // MySQL drops idle connections after wait_timeout; keepalive avoids handing out dead ones.
        // No connectionTestQuery: let Hikari use JDBC4 Connection.isValid() (Connector/J 8.3).
        hc.setKeepaliveTime(60_000);
        // Warn (stack trace) if a connection is held > 60s — diagnostic only, no eviction.
        hc.setLeakDetectionThreshold(60_000);
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        // Bounds a stalled socket read (network partition, dead server) — without this a hung
        // driver call blocks the caller thread (often the main server thread) indefinitely.
        // Non-critical: the customizer below may override for a caller with different needs.
        hc.addDataSourceProperty("connectTimeout", String.valueOf(DEFAULT_SOCKET_TIMEOUT_MS));
        hc.addDataSourceProperty("socketTimeout", String.valueOf(DEFAULT_SOCKET_TIMEOUT_MS));

        // --- escape hatch: any Hikari/driver setting the library does not model ---
        if (customizer != null) customizer.accept(hc);

        // --- security-critical settings LAST so the customizer cannot weaken them ---
        // Explicit driver class: Velocity/Paper classloader isolation breaks the DriverManager
        // ServiceLoader path; setting it loads the driver from the plugin's shaded classloader.
        hc.setJdbcUrl(jdbcUrl);
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        // Every consumer's CURRENT_TIMESTAMP/NOW() writes must land in UTC to match the
        // serverTimezone=UTC read-side assumption above — otherwise a non-UTC MySQL session
        // writes local wall-clock digits that get misread as UTC on the way back out.
        hc.setConnectionInitSql("SET time_zone = '+00:00'");
        // Pin Connector/J blast-radius flags OFF. Safe on 8.3 defaults today, but this lib is
        // shaded into every plugin — explicit pins are immune to a future driver-default flip.
        // autoDeserialize (Java-object deser RCE via BLOB) was removed in Connector/J 8.x, so it
        // is a no-op there; kept as a defensive pin for consumers that shade an older driver.
        hc.addDataSourceProperty("autoDeserialize", "false");
        hc.addDataSourceProperty("allowLoadLocalInfile", "false");   // LOCAL INFILE file-read by a rogue server
        hc.addDataSourceProperty("allowUrlInLocalInfile", "false");
        hc.addDataSourceProperty("allowMultiQueries", "false");      // stacked-query amplification
        // Optional custom truststore so VERIFY_CA / VERIFY_IDENTITY can validate a private CA.
        // Set as dataSource properties (NOT in the URL string) so the path/password never enter
        // the concatenated JDBC URL.
        if (cfg.trustStoreUrl() != null && !cfg.trustStoreUrl().isBlank()) {
            hc.addDataSourceProperty("trustCertificateKeyStoreUrl", cfg.trustStoreUrl());
            if (cfg.trustStorePassword() != null)
                hc.addDataSourceProperty("trustCertificateKeyStorePassword", cfg.trustStorePassword());
            if (cfg.trustStoreType() != null && !cfg.trustStoreType().isBlank())
                hc.addDataSourceProperty("trustCertificateKeyStoreType", cfg.trustStoreType());
        }
        this.ds = new HikariDataSource(hc);
    }

    // Package-private seam for integration tests: drive the helpers against an
    // arbitrary DataSource (e.g. H2) without MySQL pool construction/validation.
    private Database(DataSource ds, boolean debugParams) {
        this.ds = ds;
        this.debugParams = debugParams;
    }

    static Database forTesting(DataSource ds, boolean debugParams) {
        return new Database(ds, debugParams);
    }

    public Connection getConnection() throws SQLException { return ds.getConnection(); }
    public DataSource dataSource() { return ds; }

    // HikariDataSource is Closeable; H2's JdbcDataSource (test seam) is not.
    @Override public void close() {
        if (ds instanceof AutoCloseable a) { try { a.close(); } catch (Exception ignored) { } }
    }

    /**
     * <p><b>Trust contract:</b> {@code table}/{@code column}/{@code definition}/{@code columns}
     * must be DEVELOPER-controlled literals, never end-user or config-derived input. They are
     * concatenated into DDL (identifiers cannot be JDBC-bound). The whitelist guards block
     * injection, but treat these arguments as code, not data.
     *
     * <p>Add the column if missing. Swallows MySQL duplicate-column error (1060) so
     * re-running on an already-migrated table is a no-op. Identifiers validated
     * against a strict whitelist (DDL identifiers cannot be bound as parameters).
     */
    public void ensureColumn(Connection c, String table, String column, String definition) throws SQLException {
        requireIdent(table, "table");
        requireIdent(column, "column");
        requireColumnExpr(definition, "definition");
        execDdlSwallowing(c, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition, 1060); // duplicate column — fine
    }

    /**
     * <b>Trust contract:</b> arguments must be DEVELOPER-controlled literals, never user/config
     * input — they are concatenated into DDL. Convert a column to the given type (idempotent;
     * MODIFY on a matching type is a no-op).
     */
    public void ensureColumnType(Connection c, String table, String column, String typeDefinition) throws SQLException {
        requireIdent(table, "table");
        requireIdent(column, "column");
        requireColumnExpr(typeDefinition, "type");
        execDdlSwallowing(c, "ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + typeDefinition, 1146); // table doesn't exist — defensive no-op
    }

    /**
     * <b>Trust contract:</b> arguments must be DEVELOPER-controlled literals, never user/config
     * input — they are concatenated into DDL. Create the named index if absent. Swallows
     * duplicate-key-name error (1061).
     */
    public void ensureIndex(Connection c, String table, String indexName, String columns) throws SQLException {
        requireIdent(table, "table");
        requireIdent(indexName, "index");
        requireColumnExpr(columns, "columns");
        execDdlSwallowing(c, "CREATE INDEX " + indexName + " ON " + table + " " + columns, 1061); // already exists — fine
    }

    /** Shared execute+timeout+swallow-one-error-code plumbing behind ensureColumn/ensureColumnType/ensureIndex. */
    private void execDdlSwallowing(Connection c, String sql, int swallowErrorCode) throws SQLException {
        try (var st = c.createStatement()) {
            st.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
            st.execute(sql);
        } catch (SQLException e) {
            if (e.getErrorCode() == swallowErrorCode) return;
            throw e;
        }
    }

    private static void requireIdent(String v, String what) {
        if (v == null || !SAFE_IDENT.matcher(v).matches())
            throw new IllegalArgumentException("Unsafe " + what + " identifier: " + v);
    }
    private static void requireColumnExpr(String v, String what) {
        if (v == null || !SAFE_COLUMN_EXPR.matcher(v).matches())
            throw new IllegalArgumentException("Unsafe " + what + " expression: " + v);
    }

    // ---- Query helpers (synchronous; run on the caller's thread) ----

    /** Run a SELECT, mapping every row. */
    public <T> List<T> query(String sql, Sql.Binder binder, Sql.RowMapper<T> mapper) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(binder, "binder");
        Objects.requireNonNull(mapper, "mapper");
        checkNotInTx();
        return runPrepared(sql, binder, ps -> {
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        });
    }

    /** Run a SELECT, returning the first row if any. */
    public <T> Optional<T> queryFirst(String sql, Sql.Binder binder, Sql.RowMapper<T> mapper) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(binder, "binder");
        Objects.requireNonNull(mapper, "mapper");
        checkNotInTx();
        return runPrepared(sql, binder, ps -> {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.empty();
            }
        });
    }

    /** Run an INSERT/UPDATE/DELETE; returns affected rows. */
    public int update(String sql, Sql.Binder binder) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(binder, "binder");
        checkNotInTx();
        return runPrepared(sql, binder, PreparedStatement::executeUpdate);
    }

    /** One-argument functional shape for {@link #runPrepared}: act on an already-bound, already-timeout-set PreparedStatement. */
    @FunctionalInterface
    private interface PreparedAction<T> {
        T run(PreparedStatement ps) throws SQLException;
    }

    /**
     * Shared connection/prepare/timeout/bind/exception-wrap plumbing behind {@code query}/
     * {@code queryFirst}/{@code update} — only the statement-execution shape differs between them.
     */
    private <T> T runPrepared(String sql, Sql.Binder binder, PreparedAction<T> action) {
        List<Object> captured = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
            bindAndCapture(ps, binder, captured);
            return action.run(ps);
        } catch (SQLException e) {
            throw DataAccessException.wrap(sql, captured, debugParams, e);
        }
    }

    // Param-less convenience overloads.
    public <T> List<T> query(String sql, Sql.RowMapper<T> mapper) { return query(sql, Sql.Binder.NONE, mapper); }
    public <T> Optional<T> queryFirst(String sql, Sql.RowMapper<T> mapper) { return queryFirst(sql, Sql.Binder.NONE, mapper); }
    public int update(String sql) { return update(sql, Sql.Binder.NONE); }

    // ---- Async query helpers ----
    //
    // The class-level contract is "the library never spawns threads": every method below
    // requires the CALLER to supply an Executor (e.g. the plugin's own async scheduler) and
    // simply hands the existing synchronous helper to it via CompletableFuture.supplyAsync.
    // Failures surface the normal CompletableFuture way — wrapped in a CompletionException /
    // ExecutionException with the DataAccessException as the cause — not as an immediate throw.
    //
    // checkNotInTx() is called here SYNCHRONOUSLY, on the caller's own thread, before handing
    // off to the executor. inTx is a per-instance ThreadLocal set only on the thread that called
    // tx()/batch() — if the check instead ran inside the supplyAsync lambda (on the executor's
    // thread), it would never see that flag and the guard would be silently bypassed: calling
    // e.g. updateAsync(...) from inside a tx() body would open a SECOND pooled connection that
    // doesn't join the transaction (silent non-atomicity), or at small pool sizes, deadlock
    // waiting for a connection the enclosing tx is still holding.

    public <T> CompletableFuture<List<T>> queryAsync(String sql, Sql.Binder binder, Sql.RowMapper<T> mapper, Executor executor) {
        Objects.requireNonNull(executor, "executor");
        checkNotInTx();
        return CompletableFuture.supplyAsync(() -> query(sql, binder, mapper), executor);
    }

    public <T> CompletableFuture<List<T>> queryAsync(String sql, Sql.RowMapper<T> mapper, Executor executor) {
        return queryAsync(sql, Sql.Binder.NONE, mapper, executor);
    }

    public <T> CompletableFuture<Optional<T>> queryFirstAsync(String sql, Sql.Binder binder, Sql.RowMapper<T> mapper, Executor executor) {
        Objects.requireNonNull(executor, "executor");
        checkNotInTx();
        return CompletableFuture.supplyAsync(() -> queryFirst(sql, binder, mapper), executor);
    }

    public <T> CompletableFuture<Optional<T>> queryFirstAsync(String sql, Sql.RowMapper<T> mapper, Executor executor) {
        return queryFirstAsync(sql, Sql.Binder.NONE, mapper, executor);
    }

    public CompletableFuture<Integer> updateAsync(String sql, Sql.Binder binder, Executor executor) {
        Objects.requireNonNull(executor, "executor");
        checkNotInTx();
        return CompletableFuture.supplyAsync(() -> update(sql, binder), executor);
    }

    public CompletableFuture<Integer> updateAsync(String sql, Executor executor) {
        return updateAsync(sql, Sql.Binder.NONE, executor);
    }

    /** Async {@link #tx(Sql.TxBody)}. The body still runs synchronously once the executor picks it up. */
    public <T> CompletableFuture<T> txAsync(Sql.TxBody<T> body, Executor executor) {
        Objects.requireNonNull(executor, "executor");
        checkNotInTx();
        return CompletableFuture.supplyAsync(() -> tx(body), executor);
    }

    /** Async {@link #batch(String, Iterable, Sql.BiBinder)}. */
    public <T> CompletableFuture<int[]> batchAsync(String sql, Iterable<T> items, Sql.BiBinder<T> binder, Executor executor) {
        Objects.requireNonNull(executor, "executor");
        checkNotInTx();
        return CompletableFuture.supplyAsync(() -> batch(sql, items, binder), executor);
    }

    // ---- Health check ----

    /**
     * True if a pooled connection can be obtained and validated within {@code timeoutSeconds}.
     * Never throws SQLException-rooted failures (returns false instead) — but, like every other
     * helper that opens its own pooled connection, still rejects being called from inside a
     * tx()/batch() body (see {@link #checkNotInTx()}).
     */
    public boolean ping(int timeoutSeconds) {
        checkNotInTx();
        try (Connection c = ds.getConnection()) {
            return c.isValid(timeoutSeconds);
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean ping() { return ping(5); }

    public CompletableFuture<Boolean> pingAsync(int timeoutSeconds, Executor executor) {
        Objects.requireNonNull(executor, "executor");
        checkNotInTx();
        return CompletableFuture.supplyAsync(() -> ping(timeoutSeconds), executor);
    }

    public CompletableFuture<Boolean> pingAsync(Executor executor) {
        return pingAsync(5, executor);
    }

    /**
     * Pings on a fixed schedule and invokes {@code onStatusChange} only when connectivity
     * actually TRANSITIONS between up and down — not on every tick. HikariCP already retries
     * opening a new connection whenever one is requested, but gives no proactive signal that a
     * DB which died mid-run has come back; this fills that gap so a plugin can react to recovery
     * (e.g. re-enable a feature it disabled when the DB went down) instead of polling itself.
     *
     * <p>The initial state is assumed "up", so if the DB is already down at the first tick this
     * still correctly fires a "down" event. The scheduler is caller-supplied — per this class's
     * "never spawns threads" contract, nothing here creates a thread of its own.
     *
     * <p>{@code onStatusChange} is guarded: a periodic task silently stops ALL future executions
     * if it throws uncaught once, so a buggy callback must not be able to permanently disable
     * recovery monitoring for the plugin's whole lifetime.
     *
     * @return a handle whose {@code cancel(false)} stops the watchdog
     */
    public ScheduledFuture<?> watchConnection(ScheduledExecutorService scheduler, long interval, TimeUnit unit,
                                               Consumer<Boolean> onStatusChange) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(onStatusChange, "onStatusChange");
        AtomicBoolean lastUp = new AtomicBoolean(true);
        return scheduler.scheduleAtFixedRate(() -> {
            boolean up = ping();
            if (lastUp.getAndSet(up) != up) {
                try { onStatusChange.accept(up); } catch (Throwable ignored) { }
            }
        }, interval, interval, unit);
    }

    /** As {@link #watchConnection(ScheduledExecutorService, long, TimeUnit, Consumer)}, checking every 30 seconds. */
    public ScheduledFuture<?> watchConnection(ScheduledExecutorService scheduler, Consumer<Boolean> onStatusChange) {
        return watchConnection(scheduler, 30, TimeUnit.SECONDS, onStatusChange);
    }

    /**
     * Bind params through a capturing proxy so a failure can report the actual
     * values/types. We wrap the real PreparedStatement only for the set* calls
     * the binder makes; this records (index,value) without changing behavior.
     */
    private void bindAndCapture(PreparedStatement ps, Sql.Binder binder, List<Object> captured) throws SQLException {
        binder.bind(new CapturingPreparedStatement(ps, captured));
    }

    /** Reject opening a second pooled connection from inside THIS instance's tx()/batch() body. */
    private void checkNotInTx() {
        if (inTx.get()) {
            throw DataAccessException.fromBody(
                "Nested Database helper call inside a tx()/batch() body — use the passed Connection, "
                + "not another query/queryFirst/update/batch/tx (it opens a separate pooled connection, "
                + "does not join the transaction, and can self-deadlock at small pool sizes).", null);
        }
    }

    /**
     * Batched INSERT/UPDATE over {@code items}, chunked at {@value #BATCH_CHUNK}.
     * Returns per-item affected-row counts (in iteration order). All chunks run in
     * ONE transaction (autocommit off, single commit) so a failure in a later chunk
     * cannot leave earlier chunks committed — the whole batch is all-or-nothing.
     */
    public <T> int[] batch(String sql, Iterable<T> items, Sql.BiBinder<T> binder) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(binder, "binder");
        checkNotInTx();
        Connection c = null;
        List<int[]> chunkResults = new ArrayList<>();
        inTx.set(true);
        try {
            c = ds.getConnection();
            c.setAutoCommit(false);
            List<T> chunk = new ArrayList<>(BATCH_CHUNK);
            for (T item : items) {
                chunk.add(item);
                if (chunk.size() == BATCH_CHUNK) {
                    chunkResults.add(executeBatchChunk(c, sql, chunk, binder));
                    chunk.clear();
                }
            }
            if (!chunk.isEmpty()) chunkResults.add(executeBatchChunk(c, sql, chunk, binder));
            c.commit();
            int total = 0;
            for (int[] r : chunkResults) total += r.length;
            int[] result = new int[total];
            int written = 0;
            for (int[] r : chunkResults) { System.arraycopy(r, 0, result, written, r.length); written += r.length; }
            return result;
        } catch (Exception e) {
            // Catch ANY throwable from the binder — a BiBinder can throw unchecked
            // (NPE, IllegalArgumentException, ...). If rollback itself fails, the
            // connection's tx state is unknown: EVICT it so a broken/open-tx connection
            // is never returned to the shared pool.
            if (c != null) {
                try { c.rollback(); }
                catch (SQLException re) { evict(c); c = null; }
            }
            if (e instanceof DataAccessException dae) throw dae;
            if (e instanceof SQLException sqe) throw DataAccessException.wrap(sql, null, debugParams, sqe);
            throw DataAccessException.fromBody("Batch failed: " + sql, e);
        } finally {
            inTx.set(false);
            // HikariCP resets autoCommit to the pool default on return, so no manual restore.
            if (c != null) { try { c.close(); } catch (SQLException ignored) { } }
        }
    }

    /**
     * Run {@code body} inside a transaction: autocommit off, commit on success,
     * rollback + rethrow on ANY failure, connection closed in finally. If rollback
     * itself fails, the connection is EVICTED rather than returned to the pool. The
     * body gets the live connection and must use it directly (plain JDBC) for
     * multi-statement atomic units.
     *
     * <p><b>Important:</b> the other helpers ({@code query}/{@code update}/...) open
     * their OWN connection and do NOT join this transaction — inside a {@code tx}
     * body always use the passed {@code Connection c}, never {@code this.update(...)}.
     */
    public <T> T tx(Sql.TxBody<T> body) {
        Objects.requireNonNull(body, "body");
        checkNotInTx();
        Connection c = null;
        inTx.set(true);
        try {
            c = ds.getConnection();
            c.setAutoCommit(false);
            T result = body.run(c);
            c.commit();
            return result;
        } catch (Exception e) {
            // Catch ANY throwable from the body (our query helpers throw unchecked
            // DataAccessException). If rollback itself fails, EVICT the connection so a
            // broken/open-tx connection is never handed to the next plugin via the pool.
            if (c != null) {
                try { c.rollback(); }
                catch (SQLException re) { evict(c); c = null; }
            }
            if (e instanceof DataAccessException dae) throw dae;
            if (e instanceof SQLException sqe) throw DataAccessException.wrap("<transaction>", null, debugParams, sqe);
            throw DataAccessException.fromBody("Transaction body failed", e);
        } finally {
            inTx.set(false);
            // HikariCP resets autoCommit to the pool default on return, so no manual restore.
            if (c != null) { try { c.close(); } catch (SQLException ignored) { } }
        }
    }

    private <T> int[] executeBatchChunk(Connection c, String sql, List<T> chunk, Sql.BiBinder<T> binder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
            for (T item : chunk) { binder.bind(ps, item); ps.addBatch(); }
            return ps.executeBatch();
        }
    }

    /** Discard a connection whose transaction state is unknown so it never returns to the pool. */
    private void evict(Connection c) {
        if (ds instanceof com.zaxxer.hikari.HikariDataSource h) {
            try { h.evictConnection(c); } catch (Exception ignored) { }
        } else {
            try { c.close(); } catch (Exception ignored) { } // non-Hikari test seam
        }
    }
}
