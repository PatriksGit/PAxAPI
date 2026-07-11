package hu.patriksgit.paxapi.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable YAML configuration snapshot with typed getters and dot-notation key access.
 *
 * <p>Load once and hold as a {@code volatile} field — on reload, replace the reference
 * atomically so readers always see a consistent snapshot:
 * <pre>{@code
 * private volatile ConfigFile config;
 *
 * void onEnable() throws IOException {
 *     config = ConfigFile.load(dataDir.resolve("config.yml"), getResource("config.yml"), logger);
 * }
 *
 * void reload() throws IOException {
 *     config = ConfigFile.load(dataDir.resolve("config.yml"), getResource("config.yml"), logger);
 * }
 * }</pre>
 *
 * <p>If the file doesn't exist and a default {@link InputStream} is provided,
 * it is automatically extracted to disk before loading.
 *
 * <p>Dot-notation is used for nested keys: {@code "database.host"} resolves
 * {@code database → host} in the YAML tree.
 *
 * <p>All getters are null-safe — a missing or wrong-typed key returns the default value
 * with a WARN log. Use {@link #require(String)} for mandatory keys that should halt startup.
 */
public final class ConfigFile {

    private final Map<String, Object> root;
    private final String pathPrefix;
    private final Logger logger;

    private ConfigFile(Map<String, Object> root, String pathPrefix, Logger logger) {
        this.root = root;
        this.pathPrefix = pathPrefix;
        this.logger = logger;
    }

    // ── load ─────────────────────────────────────────────────────────────────

    /**
     * Loads a YAML file, auto-extracting {@code defaultResource} to disk if the file
     * doesn't exist yet.
     *
     * @param file            full path to the config file
     * @param defaultResource bundled default; copied to {@code file} if missing. May be {@code null}
     *                        if auto-extract is not needed (throws if file is also missing).
     * @param logger          SLF4J logger for type-mismatch and bounds warnings
     */
    public static ConfigFile load(Path file, InputStream defaultResource, Logger logger) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(logger, "logger");
        ensureExists(file, defaultResource);
        return new ConfigFile(readYaml(file, logger), "", logger);
    }

    /**
     * Loads a YAML file without auto-extract. The file must already exist.
     */
    public static ConfigFile load(Path file, Logger logger) throws IOException {
        return load(file, null, logger);
    }

    // ── section view ──────────────────────────────────────────────────────────

    /**
     * Returns a view of this config scoped to {@code path}.
     * Subsequent key lookups are relative to that path.
     *
     * <pre>{@code
     * ConfigFile db = config.section("database");
     * String host = db.getString("host", "localhost"); // reads "database.host"
     * }</pre>
     */
    public ConfigFile section(String path) {
        Objects.requireNonNull(path, "path");
        return new ConfigFile(root, fullKey(path), logger);
    }

    /**
     * Returns the immediate child key names at this section's level, in document
     * order. Empty if this path is missing or is not a YAML mapping. Useful for
     * iterating dynamic/user-defined keys (e.g. reward levels under a section).
     */
    @SuppressWarnings("unchecked")
    public java.util.Set<String> keys() {
        Object node = root;
        if (!pathPrefix.isEmpty()) {
            for (String part : pathPrefix.split("\\.", -1)) {
                if (!(node instanceof Map)) return java.util.Set.of();
                node = ((Map<String, Object>) node).get(part);
            }
        }
        if (node instanceof Map) {
            return new java.util.LinkedHashSet<>(((Map<String, Object>) node).keySet());
        }
        return java.util.Set.of();
    }

    // ── key presence ─────────────────────────────────────────────────────────

    /** Returns {@code true} if the key exists and is non-null in the YAML. */
    public boolean contains(String key) {
        return getRaw(key) != null;
    }

    // ── required key ─────────────────────────────────────────────────────────

    /**
     * Returns the raw string value of {@code key}.
     * Throws {@link ConfigException} if the key is missing or null — use this
     * for mandatory fields (database password, API key, etc.) that should halt startup.
     */
    public String require(String key) throws ConfigException {
        Object val = getRaw(key);
        if (val == null) throw new ConfigException("Required config key missing: '" + fullKey(key) + "'");
        if (val instanceof java.util.List || val instanceof java.util.Map)
            throw new ConfigException("Required config key '" + fullKey(key) + "' is a YAML section/list, not a scalar");
        return String.valueOf(val);
    }

    // ── typed getters ─────────────────────────────────────────────────────────

    /** Returns the string value at {@code key}, or {@code def} if missing or wrong type. */
    public String getString(String key, String def) {
        Object val = getRaw(key);
        if (val == null) return def;
        if (val instanceof List || val instanceof Map) {
            warnType(key, "string", val);
            return def;
        }
        return String.valueOf(val);
    }

    /** Returns the int value at {@code key}, or {@code def} if missing or wrong type. */
    public int getInt(String key, int def) {
        Object val = getRaw(key);
        if (val instanceof Number n) {
            double d = n.doubleValue();
            long l = n.longValue();
            boolean integral = (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte)
                               || d == Math.rint(d);
            if (integral && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE && (double) l == d) {
                return (int) l;
            }
            logger.warn("Config '{}' value {} is not a valid int (fractional or out of range) — using {}.",
                    fullKey(key), val, def);
            return def;
        }
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to warn
            }
        }
        if (val != null) warnType(key, "int", val);
        return def;
    }

    /**
     * Returns the int value at {@code key} if it lies within [{@code min}, {@code max}].
     * If the value is outside the range, logs a warning and returns {@code def}.
     */
    public int getInt(String key, int def, int min, int max) {
        int v = getInt(key, def);
        if (v < min || v > max) {
            logger.warn("Config '{}' value {} is outside allowed range [{}, {}] — using {}.",
                    fullKey(key), v, min, max, def);
            return def;
        }
        return v;
    }

    /** Returns the long value at {@code key}, or {@code def} if missing or wrong type. */
    public long getLong(String key, long def) {
        Object val = getRaw(key);
        if (val instanceof Number n) {
            if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
                return n.longValue();
            }
            // BigInteger only ever arises here when SafeConstructor's YAML int resolver already
            // determined the value exceeds long range — always reject outright. Do NOT attempt
            // double-based recovery: casting Long.MAX_VALUE back to double rounds UP to exactly
            // 2^63 (doubles near 2^63 are spaced 1024 apart, and MAX_VALUE is only 1 below 2^63),
            // which made the old "(double) l == d" check spuriously pass for BigInteger values
            // one past either boundary — silently returning MAX_VALUE/MIN_VALUE as if validated.
            if (n instanceof java.math.BigInteger) {
                logger.warn("Config '{}' value {} is out of long range — using {}.", fullKey(key), val, def);
                return def;
            }
            // floating-point type: accept only if no fractional part and within long range
            double d = n.doubleValue();
            if (d == Math.rint(d)) {
                long l = (long) d;
                if ((double) l == d) {
                    return l;
                }
            }
            logger.warn("Config '{}' value {} is not a valid long (fractional or out of range) — using {}.",
                    fullKey(key), val, def);
            return def;
        }
        if (val instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to warn
            }
        }
        if (val != null) warnType(key, "long", val);
        return def;
    }

    /** Returns the double value at {@code key}, or {@code def} if missing or wrong type. */
    public double getDouble(String key, double def) {
        Object val = getRaw(key);
        if (val instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                logger.warn("Config '{}' value {} is not a finite double — using {}.",
                        fullKey(key), val, def);
                return def;
            }
            return d;
        }
        if (val instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim());
                if (!Double.isFinite(d)) {
                    logger.warn("Config '{}' value {} is not a finite double — using {}.",
                            fullKey(key), val, def);
                    return def;
                }
                return d;
            } catch (NumberFormatException ignored) {
                // fall through to warn
            }
        }
        if (val != null) warnType(key, "double", val);
        return def;
    }

    /**
     * Returns the float value at {@code key}, or {@code def} if missing or wrong type. Useful
     * for Minecraft's float-typed conventions (sound volume/pitch, etc.).
     */
    public float getFloat(String key, float def) {
        Object val = getRaw(key);
        if (val instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                logger.warn("Config '{}' value {} is not a finite float — using {}.", fullKey(key), val, def);
                return def;
            }
            float f = (float) d;
            if (!Float.isFinite(f)) {
                logger.warn("Config '{}' value {} is out of float range — using {}.", fullKey(key), val, def);
                return def;
            }
            return f;
        }
        if (val instanceof String s) {
            try {
                float f = Float.parseFloat(s.trim());
                if (!Float.isFinite(f)) {
                    logger.warn("Config '{}' value {} is not a finite float — using {}.", fullKey(key), val, def);
                    return def;
                }
                return f;
            } catch (NumberFormatException ignored) {
                // fall through to warn
            }
        }
        if (val != null) warnType(key, "float", val);
        return def;
    }

    /** Returns the boolean value at {@code key}, or {@code def} if missing or wrong type. */
    public boolean getBoolean(String key, boolean def) {
        Object val = getRaw(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) {
            String lower = s.trim().toLowerCase(Locale.ROOT);
            if (lower.equals("true"))  return true;
            if (lower.equals("false")) return false;
            // fall through to warnType + return def
        }
        if (val != null) warnType(key, "boolean", val);
        return def;
    }

    /**
     * Returns a string list at {@code key}.
     * Handles both YAML list values and single-string values (wraps to 1-element list).
     * Null elements within a list are converted to empty strings.
     * Returns {@code def} if the key is missing, or if the value is neither a list
     * nor a string (logs a WARN).
     */
    public List<String> getStringList(String key, List<String> def) {
        Object val = getRaw(key);
        if (val instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(item != null ? String.valueOf(item) : "");
            return Collections.unmodifiableList(result);
        }
        if (val instanceof String s) return List.of(s);
        if (val != null) warnType(key, "list", val);
        return def;
    }

    /** As {@link #getStringList(String, List)}, defaulting to an empty list when missing/wrong-typed. */
    public List<String> getStringList(String key) {
        return getStringList(key, List.of());
    }

    /**
     * Returns the enum value at {@code key} (case-insensitive), or {@code def} if missing
     * or not a valid enum constant.
     */
    public <E extends Enum<E>> E getEnum(Class<E> type, String key, E def) {
        String val = getString(key, null);
        if (val == null) return def;
        try {
            return Enum.valueOf(type, val.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warn("Config '{}' value '{}' is not a valid {} — using {}.",
                    fullKey(key), val, type.getSimpleName(), def);
            return def;
        }
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private static void ensureExists(Path file, InputStream defaultResource) throws IOException {
        try (InputStream in = defaultResource) {
            if (Files.exists(file)) return;
            if (in == null)
                throw new IOException("Config file not found and no default provided: " + file);
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            try {
                Files.copy(in, file);
            } catch (FileAlreadyExistsException ignored) {
                // another thread/process created the file concurrently — that's fine
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYaml(Path file, Logger logger) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            Object parsed;
            try {
                LoaderOptions opts = new LoaderOptions();
                opts.setAllowDuplicateKeys(false);
                parsed = new Yaml(new SafeConstructor(opts)).load(in);
            } catch (YAMLException e) {
                throw new ConfigException("Malformed YAML in " + file + " (see cause for details)", e);
            }
            if (parsed == null) {
                logger.warn("{} is empty — using empty config.", file.getFileName());
                return Map.of();
            }
            if (!(parsed instanceof Map)) {
                throw new ConfigException("Config file root must be a YAML mapping: " + file);
            }
            return (Map<String, Object>) deepImmutable(parsed);
        }
    }

    private static Object deepImmutable(Object o) throws ConfigException {
        return deepImmutable(o, new java.util.IdentityHashMap<>());
    }

    /**
     * {@code visiting} tracks containers currently on the walk's ANCESTOR path (entered, not
     * yet finished) — not "ever seen". A YAML anchor reused in two unrelated sibling keys
     * (e.g. {@code defaults: &d ... ; x: *d ; y: *d}) is legitimate, common aliasing and must
     * not be flagged; only a container that is its OWN ancestor (a true cycle, e.g.
     * {@code a: &x \n  loop: *x}) is a problem. SnakeYAML's composer registers an anchor before
     * composing its children, so this is legal, safely-constructed YAML — but walking it here
     * with no cycle guard recurses forever (StackOverflowError). Entries are removed in a
     * finally block on exit so completed subtrees don't poison later, unrelated references.
     */
    @SuppressWarnings("unchecked")
    private static Object deepImmutable(Object o, Map<Object, Boolean> visiting) throws ConfigException {
        if (o instanceof Map<?, ?> || o instanceof List<?>) {
            if (visiting.put(o, Boolean.TRUE) != null) {
                throw new ConfigException("Config contains a self-referential YAML anchor/alias — not supported");
            }
            try {
                if (o instanceof Map<?, ?> rawMap) {
                    Map<Object, Object> src = (Map<Object, Object>) rawMap;
                    Map<String, Object> copy = new LinkedHashMap<>(src.size());
                    for (Map.Entry<Object, Object> e : src.entrySet()) {
                        copy.put(String.valueOf(e.getKey()), deepImmutable(e.getValue(), visiting));
                    }
                    return Collections.unmodifiableMap(copy);
                }
                List<Object> src = (List<Object>) o;
                List<Object> copy = new ArrayList<>(src.size());
                for (Object item : src) copy.add(deepImmutable(item, visiting));
                return Collections.unmodifiableList(copy);
            } finally {
                visiting.remove(o);
            }
        }
        return o;
    }

    private String fullKey(String key) {
        return pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
    }

    @SuppressWarnings("unchecked")
    private Object getRaw(String key) {
        String[] parts = fullKey(key).split("\\.", -1);
        Object current = root;
        for (String part : parts) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    private void warnType(String key, String expected, Object actual) {
        logger.warn("Config '{}' expected {} but got {} — using default.",
                fullKey(key), expected, actual.getClass().getSimpleName());
    }
}
