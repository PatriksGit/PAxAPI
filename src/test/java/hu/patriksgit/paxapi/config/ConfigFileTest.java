package hu.patriksgit.paxapi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigFileTest {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigFileTest.class);

    @TempDir
    Path tempDir;

    private Path yaml(String content) throws IOException {
        Path f = tempDir.resolve("config.yml");
        Files.writeString(f, content);
        return f;
    }

    private static ConfigFile load(Path f) throws IOException {
        return ConfigFile.load(f, LOG);
    }

    // ── getString ────────────────────────────────────────────────────────────

    @Test
    void getString() throws IOException {
        assertEquals("localhost", load(yaml("host: localhost")).getString("host", "def"));
    }

    @Test
    void keysReturnsChildKeysInOrder() throws IOException {
        ConfigFile c = load(yaml("milestones:\n  10:\n    a: 1\n  100:\n    a: 2\n  50:\n    a: 3\n"));
        assertEquals(List.of("10", "100", "50"),
            new java.util.ArrayList<>(c.section("milestones").keys()));
    }

    @Test
    void keysEmptyForMissingOrScalar() throws IOException {
        ConfigFile c = load(yaml("host: localhost\n"));
        assertTrue(c.section("nope").keys().isEmpty());
        assertTrue(c.section("host").keys().isEmpty());
    }

    @Test
    void getStringMissingReturnsDefault() throws IOException {
        assertEquals("def", load(yaml("a: b")).getString("missing", "def"));
    }

    @Test
    void getStringFromInt() throws IOException {
        assertEquals("42", load(yaml("port: 42")).getString("port", "def"));
    }

    // ── getInt ───────────────────────────────────────────────────────────────

    @Test
    void getInt() throws IOException {
        assertEquals(3306, load(yaml("port: 3306")).getInt("port", 0));
    }

    @Test
    void getIntMissingReturnsDefault() throws IOException {
        assertEquals(99, load(yaml("a: b")).getInt("missing", 99));
    }

    @Test
    void getIntWrongTypeReturnsDefault() throws IOException {
        assertEquals(5, load(yaml("port: notanumber")).getInt("port", 5));
    }

    @Test
    void getIntBoundedInRange() throws IOException {
        assertEquals(3, load(yaml("n: 3")).getInt("n", 1, 1, 10));
    }

    @Test
    void getIntBoundedOutOfRangeReturnsDefault() throws IOException {
        assertEquals(1, load(yaml("n: 99")).getInt("n", 1, 1, 10));
    }

    @Test
    void getIntBoundedBelowMinReturnsDefault() throws IOException {
        assertEquals(5, load(yaml("n: 0")).getInt("n", 5, 1, 10));
    }

    // ── getLong ──────────────────────────────────────────────────────────────

    @Test
    void getLong() throws IOException {
        assertEquals(9999999999L, load(yaml("big: 9999999999")).getLong("big", 0L));
    }

    // ── getDouble ────────────────────────────────────────────────────────────

    @Test
    void getDouble() throws IOException {
        assertEquals(1.5, load(yaml("ratio: 1.5")).getDouble("ratio", 0.0), 0.001);
    }

    // ── getBoolean ───────────────────────────────────────────────────────────

    @Test
    void getBooleanTrue() throws IOException {
        assertTrue(load(yaml("enabled: true")).getBoolean("enabled", false));
    }

    @Test
    void getBooleanFalse() throws IOException {
        assertFalse(load(yaml("enabled: false")).getBoolean("enabled", true));
    }

    @Test
    void getBooleanMissingReturnsDefault() throws IOException {
        assertTrue(load(yaml("a: b")).getBoolean("missing", true));
    }

    // ── getStringList ────────────────────────────────────────────────────────

    @Test
    void getStringList() throws IOException {
        ConfigFile cfg = load(yaml("items:\n  - a\n  - b\n  - c"));
        assertEquals(List.of("a", "b", "c"), cfg.getStringList("items", List.of()));
    }

    @Test
    void getStringListSingleStringWrapped() throws IOException {
        assertEquals(List.of("hello"), load(yaml("msg: hello")).getStringList("msg", List.of()));
    }

    @Test
    void getStringListMissingReturnsDefault() throws IOException {
        assertEquals(List.of("x"), load(yaml("a: b")).getStringList("missing", List.of("x")));
    }

    // ── getEnum ──────────────────────────────────────────────────────────────

    enum Color { RED, GREEN, BLUE }

    @Test
    void getEnum() throws IOException {
        assertEquals(Color.GREEN, load(yaml("color: green")).getEnum(Color.class, "color", Color.RED));
    }

    @Test
    void getEnumCaseInsensitive() throws IOException {
        assertEquals(Color.BLUE, load(yaml("color: BLUE")).getEnum(Color.class, "color", Color.RED));
    }

    @Test
    void getEnumInvalidReturnsDefault() throws IOException {
        assertEquals(Color.RED, load(yaml("color: YELLOW")).getEnum(Color.class, "color", Color.RED));
    }

    @Test
    void getEnumMissingReturnsDefault() throws IOException {
        assertEquals(Color.RED, load(yaml("a: b")).getEnum(Color.class, "missing", Color.RED));
    }

    // ── dot-notation nested keys ─────────────────────────────────────────────

    @Test
    void nestedDotKey() throws IOException {
        ConfigFile cfg = load(yaml("database:\n  host: db.local\n  port: 5432"));
        assertEquals("db.local", cfg.getString("database.host", "def"));
        assertEquals(5432, cfg.getInt("database.port", 0));
    }

    @Test
    void nestedMissingReturnsDefault() throws IOException {
        ConfigFile cfg = load(yaml("database:\n  host: db.local"));
        assertEquals(99, cfg.getInt("database.port", 99));
    }

    // ── section() ────────────────────────────────────────────────────────────

    @Test
    void sectionView() throws IOException {
        ConfigFile cfg = load(yaml("database:\n  host: db.local\n  port: 5432"));
        ConfigFile db = cfg.section("database");
        assertEquals("db.local", db.getString("host", "def"));
        assertEquals(5432, db.getInt("port", 0));
    }

    @Test
    void sectionMissingKeyReturnsDefault() throws IOException {
        ConfigFile db = load(yaml("database:\n  host: db.local")).section("database");
        assertEquals(3306, db.getInt("port", 3306));
    }

    @Test
    void nestedSection() throws IOException {
        ConfigFile cfg = load(yaml("a:\n  b:\n    c: 42"));
        assertEquals(42, cfg.section("a").section("b").getInt("c", 0));
    }

    // ── contains ────────────────────────────────────────────────────────────

    @Test
    void containsExistingKey() throws IOException {
        assertTrue(load(yaml("host: localhost")).contains("host"));
    }

    @Test
    void containsMissingKey() throws IOException {
        assertFalse(load(yaml("host: localhost")).contains("port"));
    }

    // ── require() ────────────────────────────────────────────────────────────

    @Test
    void requireExistingKey() throws IOException {
        assertEquals("secret", load(yaml("password: secret")).require("password"));
    }

    @Test
    void requireMissingKeyThrows() throws IOException {
        assertThrows(ConfigException.class, () -> load(yaml("a: b")).require("missing"));
    }

    // ── auto-extract ─────────────────────────────────────────────────────────

    @Test
    void autoExtractsFromDefaultResource() throws IOException {
        Path f = tempDir.resolve("new.yml");
        byte[] content = "host: extracted".getBytes();
        ConfigFile cfg = ConfigFile.load(f, new ByteArrayInputStream(content), LOG);
        assertTrue(Files.exists(f));
        assertEquals("extracted", cfg.getString("host", "def"));
    }

    @Test
    void throwsIfMissingAndNoDefault() {
        Path f = tempDir.resolve("nonexistent.yml");
        assertThrows(IOException.class, () -> ConfigFile.load(f, LOG));
    }

    // ── empty file ───────────────────────────────────────────────────────────

    @Test
    void emptyFileReturnsDefaults() throws IOException {
        Path f = tempDir.resolve("empty.yml");
        Files.writeString(f, "");
        ConfigFile cfg = ConfigFile.load(f, LOG);
        assertEquals("def", cfg.getString("any", "def"));
    }

    // ── hot-reload pattern ───────────────────────────────────────────────────

    @Test
    void reloadByReplacingReference() throws IOException {
        Path f = yaml("version: 1");
        ConfigFile v1 = load(f);
        assertEquals(1, v1.getInt("version", 0));

        Files.writeString(f, "version: 2");
        ConfigFile v2 = load(f);
        assertEquals(2, v2.getInt("version", 0));
        assertEquals(1, v1.getInt("version", 0)); // old snapshot unchanged
    }

    // ── FIX 1: getInt/getLong truncation/overflow guards ────────────────────

    @Test
    void getIntFractionalReturnsDefault() throws IOException {
        // 3.9 is a Double in YAML — must NOT silently truncate to 3
        assertEquals(99, load(yaml("port: 3.9")).getInt("port", 99));
    }

    @Test
    void getIntLongOverflowReturnsDefault() throws IOException {
        // 9999999999 exceeds Integer.MAX_VALUE — must NOT wrap to negative
        assertEquals(99, load(yaml("port: 9999999999")).getInt("port", 99));
    }

    @Test
    void getIntExactIntegerWorks() throws IOException {
        assertEquals(3306, load(yaml("port: 3306")).getInt("port", 0));
    }

    @Test
    void getLongBigValueWorks() throws IOException {
        assertEquals(9999999999L, load(yaml("big: 9999999999")).getLong("big", 0L));
    }

    @Test
    void getLongFractionalReturnsDefault() throws IOException {
        // 3.9 is a Double in YAML — must NOT truncate to 3L
        assertEquals(99L, load(yaml("val: 3.9")).getLong("val", 99L));
    }

    // ── FIX 2: quoted-number fallback ────────────────────────────────────────

    @Test
    void getIntQuotedNumberParsed() throws IOException {
        assertEquals(3306, load(yaml("port: \"3306\"")).getInt("port", 0));
    }

    @Test
    void getIntQuotedNonNumberReturnsDefault() throws IOException {
        assertEquals(99, load(yaml("port: \"abc\"")).getInt("port", 99));
    }

    @Test
    void getDoubleQuotedNumberParsed() throws IOException {
        assertEquals(1.5, load(yaml("ratio: \"1.5\"")).getDouble("ratio", 0.0), 0.001);
    }

    @Test
    void getLongQuotedNumberParsed() throws IOException {
        assertEquals(9999999999L, load(yaml("big: \"9999999999\"")).getLong("big", 0L));
    }

    // ── FIX 3: getString does not stringify collections ───────────────────────

    @Test
    void getStringOnListReturnsDefault() throws IOException {
        String result = load(yaml("items:\n  - a\n  - b")).getString("items", "def");
        assertEquals("def", result);
    }

    @Test
    void getStringOnMapReturnsDefault() throws IOException {
        String result = load(yaml("db:\n  host: localhost")).getString("db", "def");
        assertEquals("def", result);
    }

    @Test
    void getStringScalarStillStringifies() throws IOException {
        // Integer scalar must still stringify (existing behaviour)
        assertEquals("42", load(yaml("port: 42")).getString("port", "def"));
    }

    // ── FIX 4: malformed YAML throws ConfigException ─────────────────────────

    @Test
    void malformedYamlThrowsConfigException() throws IOException {
        Path f = tempDir.resolve("bad.yml");
        Files.writeString(f, "a:\n  b: [unclosed");
        assertThrows(ConfigException.class, () -> ConfigFile.load(f, LOG));
    }

    // ── getDouble NaN/Infinity guard ─────────────────────────────────────────

    @Test
    void getDoubleNanReturnsDefault() throws IOException {
        // .nan parses to Double.NaN — not finite, must return default
        assertEquals(1.0, load(yaml("ratio: .nan")).getDouble("ratio", 1.0), 0.001);
    }

    @Test
    void getDoubleInfinityReturnsDefault() throws IOException {
        // .inf parses to Double.POSITIVE_INFINITY — not finite, must return default
        assertEquals(1.0, load(yaml("ratio: .inf")).getDouble("ratio", 1.0), 0.001);
    }

    @Test
    void getDoubleQuotedNanReturnsDefault() throws IOException {
        // String path: "NaN" parses via Double.parseDouble to NaN — must return default
        assertEquals(1.0, load(yaml("ratio: \"NaN\"")).getDouble("ratio", 1.0), 0.001);
    }

    @Test
    void getDoubleQuotedInfinityReturnsDefault() throws IOException {
        // String path: "Infinity" parses to POSITIVE_INFINITY — must return default
        assertEquals(1.0, load(yaml("ratio: \"Infinity\"")).getDouble("ratio", 1.0), 0.001);
    }

    // ── allowDuplicateKeys(false) ────────────────────────────────────────────

    @Test
    void duplicateKeyThrowsConfigException() throws IOException {
        assertThrows(ConfigException.class, () -> load(yaml("a: 1\na: 2")));
    }

    // ── deep immutability ────────────────────────────────────────────────────

    @Test
    void getStringListResultIsUnmodifiable() throws IOException {
        List<String> result = load(yaml("items:\n  - a\n  - b")).getStringList("items", List.of());
        assertThrows(UnsupportedOperationException.class, () -> result.add("c"));
    }

    // ── non-String YAML keys reachable ───────────────────────────────────────

    @Test
    void integerKeyReachableAsString() throws IOException {
        // 123 is an Integer key in YAML — String.valueOf(key) makes it reachable as "123"
        assertEquals("foo", load(yaml("123: foo")).getString("123", "def"));
    }

    // ── getStringList wrong scalar type ──────────────────────────────────────

    @Test
    void getStringListOnIntReturnsDefault() throws IOException {
        // Integer scalar is neither a List nor a String — must return default
        assertEquals(List.of("x"), load(yaml("n: 42")).getStringList("n", List.of("x")));
    }

    @Test
    void getStringListOnBooleanReturnsDefault() throws IOException {
        // Boolean scalar is neither a List nor a String — must return default
        assertEquals(List.of("x"), load(yaml("n: true")).getStringList("n", List.of("x")));
    }

    // ── contains with explicit null ──────────────────────────────────────────

    @Test
    void containsExplicitNullReturnsFalse() throws IOException {
        // key: ~ is an explicit YAML null — contains must return false
        assertFalse(load(yaml("key: ~")).contains("key"));
    }

    // ── getBoolean quoted-string ─────────────────────────────────────────────

    @Test
    void getBooleanQuotedTrue() throws IOException {
        assertTrue(load(yaml("enabled: \"true\"")).getBoolean("enabled", false));
    }

    @Test
    void getBooleanQuotedFalse() throws IOException {
        assertFalse(load(yaml("enabled: \"false\"")).getBoolean("enabled", true));
    }

    @Test
    void getBooleanArbitraryStringReturnsDefault() throws IOException {
        assertTrue(load(yaml("enabled: \"maybe\"")).getBoolean("enabled", true));
        assertFalse(load(yaml("enabled: \"maybe\"")).getBoolean("enabled", false));
    }
}
