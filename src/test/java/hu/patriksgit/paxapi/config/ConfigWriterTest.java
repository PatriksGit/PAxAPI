package hu.patriksgit.paxapi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
