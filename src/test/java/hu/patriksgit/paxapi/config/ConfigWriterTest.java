package hu.patriksgit.paxapi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConfigWriterTest {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigWriterTest.class);

    @TempDir
    Path tempDir;

    @Test void savesFlatMapAndReadsBackViaConfigFile() throws IOException {
        Path file = tempDir.resolve("side.yml");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("host", "localhost");
        data.put("port", 25565);

        ConfigWriter.save(file, data);

        ConfigFile cfg = ConfigFile.load(file, LOG);
        assertEquals("localhost", cfg.getString("host", "def"));
        assertEquals(25565, cfg.getInt("port", 0));
    }

    @Test void saveOverwritesExistingFileCompletely() throws IOException {
        Path file = tempDir.resolve("side.yml");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("keep", "no");
        a.put("shared", "old");
        ConfigWriter.save(file, a);

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("shared", "new");
        ConfigWriter.save(file, b);

        ConfigFile cfg = ConfigFile.load(file, LOG);
        assertFalse(cfg.contains("keep"));
        assertEquals("new", cfg.getString("shared", "def"));
    }

    @Test void saveIsAtomicNoTmpFileLeftBehind() throws IOException {
        Path file = tempDir.resolve("side.yml");
        ConfigWriter.save(file, Map.of("a", "b"));

        Path tmpFile = tempDir.resolve("side.yml.tmp");
        assertFalse(Files.exists(tmpFile));
    }

    @Test void saveCreatesParentDirectoriesIfMissing() throws IOException {
        Path file = tempDir.resolve("nested/dir/side.yml");
        ConfigWriter.save(file, Map.of("a", "b"));

        assertTrue(Files.exists(file));
        ConfigFile cfg = ConfigFile.load(file, LOG);
        assertEquals("b", cfg.getString("a", "def"));
    }

    @Test void saveWithHeaderCommentPrependsRawTextBeforeYaml() throws IOException {
        Path file = tempDir.resolve("side.yml");
        String header = "# this is a header\n";
        ConfigWriter.save(file, Map.of("a", "b"), header);

        String content = Files.readString(file);
        assertTrue(content.startsWith(header), "expected header at the start, got: " + content);
    }

    @Test void saveWithHeaderCommentRejectsNullHeaderComment() {
        Path file = tempDir.resolve("side.yml");
        assertThrows(NullPointerException.class, () -> ConfigWriter.save(file, Map.of("a", "b"), null));
    }

    @Test void saveRejectsNullFile() {
        assertThrows(NullPointerException.class, () -> ConfigWriter.save(null, Map.of("a", "b")));
    }

    @Test void saveRejectsNullData() {
        Path file = tempDir.resolve("side.yml");
        assertThrows(NullPointerException.class, () -> ConfigWriter.save(file, null));
    }

    @Test void upsertMissingCreatesFileWhenAbsentWithAllCandidates() throws IOException {
        Path file = tempDir.resolve("worlds.yml");
        Map<String, Object> candidates = new LinkedHashMap<>();
        candidates.put("world", Map.of("enderpearl-cooldown", 16));
        candidates.put("world_nether", Map.of("enderpearl-cooldown", 8));

        Set<String> added = ConfigWriter.upsertMissing(file, candidates, LOG);

        assertEquals(Set.of("world", "world_nether"), added);
        ConfigFile cfg = ConfigFile.load(file, LOG);
        assertEquals(16, cfg.getInt("world.enderpearl-cooldown", 0));
        assertEquals(8, cfg.getInt("world_nether.enderpearl-cooldown", 0));
    }

    @Test void upsertMissingAddsOnlyTrulyMissingKeysAndLeavesExistingBlocksUntouched() throws IOException {
        Path file = tempDir.resolve("worlds.yml");
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("world", Map.of("enderpearl-cooldown", 99)); // customized, differs from candidate default
        ConfigWriter.save(file, existing);

        Map<String, Object> candidates = new LinkedHashMap<>();
        candidates.put("world", Map.of("enderpearl-cooldown", 16)); // would-be default, must NOT overwrite
        candidates.put("world_nether", Map.of("enderpearl-cooldown", 8));

        Set<String> added = ConfigWriter.upsertMissing(file, candidates, LOG);

        assertEquals(Set.of("world_nether"), added);
        ConfigFile cfg = ConfigFile.load(file, LOG);
        assertEquals(99, cfg.getInt("world.enderpearl-cooldown", 0)); // untouched
        assertEquals(8, cfg.getInt("world_nether.enderpearl-cooldown", 0));
    }

    @Test void upsertMissingTreatsExistingNullValueAsPresentNotMissing() throws IOException {
        Path file = tempDir.resolve("worlds.yml");
        Files.writeString(file, "world: null\n");

        Map<String, Object> candidates = Map.of("world", Map.of("enderpearl-cooldown", 16));
        Set<String> added = ConfigWriter.upsertMissing(file, candidates, LOG);

        assertTrue(added.isEmpty(), "existing null-valued key must not be reported as added");

        Map<?, ?> raw = new org.yaml.snakeyaml.Yaml().load(Files.newBufferedReader(file));
        assertTrue(raw.containsKey("world"), "key must still be present on disk");
        assertNull(raw.get("world"), "existing null value must remain null, not be overwritten");
    }

    @Test void upsertMissingReturnsEmptySetAndDoesNotWriteWhenNothingToAdd() throws IOException {
        Path file = tempDir.resolve("worlds.yml");
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("world", Map.of("enderpearl-cooldown", 16));
        ConfigWriter.save(file, existing);
        // Pin the mtime to an arbitrary fixed value so ANY write at all (even one producing
        // byte-identical content) would be detectable — no reliance on real-time sleeps or
        // filesystem timestamp resolution.
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(123_456_000L));

        Set<String> added = ConfigWriter.upsertMissing(file, Map.of("world", Map.of("enderpearl-cooldown", 999)), LOG);

        assertTrue(added.isEmpty());
        assertEquals(123_456_000L, Files.getLastModifiedTime(file).toMillis());
    }

    @Test void upsertMissingSupportsNestedStructuredValues() throws IOException {
        Path file = tempDir.resolve("worlds.yml");
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("enderpearl-cooldown", 16);
        block.put("enderpearl-distance", 8.0);
        block.put("crystal-explodes", true);
        Map<String, Object> candidates = Map.of("world", block);

        ConfigWriter.upsertMissing(file, candidates, LOG);

        ConfigFile cfg = ConfigFile.load(file, LOG).section("world");
        assertEquals(16, cfg.getInt("enderpearl-cooldown", 0));
        assertEquals(8.0, cfg.getDouble("enderpearl-distance", 0));
        assertTrue(cfg.getBoolean("crystal-explodes", false));
    }

    @Test void upsertMissingLogsWarnAndTreatsExistingEmptyFileAsEmptyMap() throws IOException {
        Path file = tempDir.resolve("worlds.yml");
        Files.writeString(file, "   \n");
        Logger mockLogger = mock(Logger.class);

        Set<String> added = ConfigWriter.upsertMissing(file, Map.of("world", Map.of("a", 1)), mockLogger);

        assertEquals(Set.of("world"), added);
        verify(mockLogger).warn(anyString(), any(Object.class));
    }

    @Test void upsertMissingThrowsConfigExceptionOnMalformedYaml() throws IOException {
        Path file = tempDir.resolve("worlds.yml");
        Files.writeString(file, "world: [unclosed\n");

        assertThrows(ConfigException.class, () -> ConfigWriter.upsertMissing(file, Map.of("a", "b"), LOG));
    }

    @Test void upsertMissingThrowsConfigExceptionWhenRootIsNotAMapping() throws IOException {
        Path file = tempDir.resolve("worlds.yml");
        Files.writeString(file, "- one\n- two\n");

        assertThrows(ConfigException.class, () -> ConfigWriter.upsertMissing(file, Map.of("a", "b"), LOG));
    }

    @Test void upsertMissingPreservesCandidateIterationOrderInAddedSet() throws IOException {
        Path file = tempDir.resolve("worlds.yml");
        Map<String, Object> candidates = new LinkedHashMap<>();
        candidates.put("zeta", Map.of("a", 1));
        candidates.put("alpha", Map.of("a", 1));
        candidates.put("mid", Map.of("a", 1));

        Set<String> added = ConfigWriter.upsertMissing(file, candidates, LOG);

        assertEquals(List.of("zeta", "alpha", "mid"), new ArrayList<>(added));
    }

    @Test void upsertMissingRejectsNullFile() {
        assertThrows(NullPointerException.class, () -> ConfigWriter.upsertMissing(null, Map.of("a", "b"), LOG));
    }

    @Test void upsertMissingRejectsNullCandidates() {
        Path file = tempDir.resolve("worlds.yml");
        assertThrows(NullPointerException.class, () -> ConfigWriter.upsertMissing(file, null, LOG));
    }

    @Test void upsertMissingRejectsNullLogger() {
        Path file = tempDir.resolve("worlds.yml");
        assertThrows(NullPointerException.class, () -> ConfigWriter.upsertMissing(file, Map.of("a", "b"), null));
    }
}
