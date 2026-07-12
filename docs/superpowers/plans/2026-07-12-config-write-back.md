# Config Write-Back (ConfigWriter) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `ConfigWriter` class to PAxAPI (`hu.patriksgit.paxapi.config`) providing atomic YAML write-back for machine-managed side-files — replacing the hand-rolled tmp-file + `ATOMIC_MOVE` persistence duplicated across PAxAuth's Paper and Velocity modules, plus a new idempotent upsert-missing-keys operation needed by a future consumer (PAxPvpManager).

**Architecture:** One new class with three static methods: `save(file, data)` / `save(file, data, headerComment)` (full-document atomic overwrite) and `upsertMissing(file, candidates, logger)` (loads if present, adds only genuinely-missing root-level keys, persists via `save` internally, returns the added key set). `ConfigFile` is untouched — `ConfigWriter` has its own small, self-contained YAML read path for `upsertMissing`, deliberately not sharing code with `ConfigFile.readYaml` (see Global Constraints).

**Tech Stack:** Java 21, JUnit 5.10.2 (Jupiter), Mockito 5.11.0, SnakeYAML (already a dependency via `ConfigFile`), Maven. No new dependencies.

## Global Constraints

- Package: `hu.patriksgit.paxapi.config` (same package as `ConfigFile`/`ConfigException`).
- `ConfigFile.java` is **not modified** by this plan — `ConfigWriter` has its own private YAML parse method, duplicating the small (~15-line) read/error-wrap logic rather than extracting a shared helper, to keep zero regression risk on the existing, already-shipped, 76-test-covered `ConfigFile.java`.
- `save(file, data)` is a **full overwrite**, never a partial merge — the caller always supplies the complete desired document.
- `save`'s 3-arg `headerComment` overload rejects `null` (`Objects.requireNonNull`) — the 2-arg overload is the "no header" path; there is no null-means-same-as-2-arg equivalence.
- `upsertMissing`'s presence check is `containsKey`, **never** `get(key) != null` — an existing key mapped to YAML `null` counts as present and must not be overwritten. The implementation must use a plain explicit loop (`if (!loaded.containsKey(k)) { loaded.put(k, v); added.add(k); }`) — `putIfAbsent`/`computeIfAbsent` are disallowed as implementation strategies here because both treat an existing `null` value as absent.
- `upsertMissing`'s `candidates` values must be non-null — this is a documented contract restriction, not runtime-enforced; no `null`-candidate-value test is included (no real call site needs it — YAGNI).
- `upsertMissing`'s only use of its `logger` parameter is warning when the target file exists but parses to an empty document (mirrors `ConfigFile.readYaml`'s identical behavior for the identical structural case). It is not used for malformed YAML or non-mapping-root (those throw), nor for "existing key has a different shape than the candidate" (silently left alone by design).
- If `upsertMissing` finds nothing to add, it must not write to the file at all (no tmp file, no move, mtime untouched).
- Error handling: `Objects.requireNonNull` on every reference parameter (`NullPointerException`); YAML-shape errors (malformed YAML, non-mapping root) on read → `ConfigException` with wording matching `ConfigFile.readYaml`'s existing messages exactly; raw I/O failures propagate unwrapped.
- Spec: `docs/superpowers/specs/2026-07-12-config-write-back-design.md`.

---

### Task 1: `ConfigWriter.save` — full-document atomic write

**Files:**
- Create: `src/main/java/hu/patriksgit/paxapi/config/ConfigWriter.java`
- Test: `src/test/java/hu/patriksgit/paxapi/config/ConfigWriterTest.java`

**Interfaces:**
- Produces: `ConfigWriter.save(Path file, Map<String, Object> data) -> void` (throws `IOException`),
  `ConfigWriter.save(Path file, Map<String, Object> data, String headerComment) -> void` (throws `IOException`).
  Task 2 adds a fourth static method (`upsertMissing`) to this same file/class, which internally calls the 2-arg `save`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/hu/patriksgit/paxapi/config/ConfigWriterTest.java`:

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ConfigWriterTest test`
Expected: compile error — `ConfigWriter` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/hu/patriksgit/paxapi/config/ConfigWriter.java`:

```java
package hu.patriksgit.paxapi.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;

/**
 * Atomic YAML write-back for machine-managed side-files — never the human-edited
 * {@code config.yml} (see {@link ConfigFile}'s Javadoc: a plain YAML dump would silently strip
 * every comment). {@link #save} overwrites the whole document; {@code upsertMissing} (added
 * alongside this class) backfills only genuinely-missing root-level keys.
 */
public final class ConfigWriter {
    private ConfigWriter() {}

    /** Writes {@code data} as the complete document, atomically. No header — see {@link #save(Path, Map, String)}. */
    public static void save(Path file, Map<String, Object> data) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(data, "data");
        writeAtomic(file, data, null);
    }

    /**
     * As {@link #save(Path, Map)}, plus {@code headerComment} is written verbatim immediately
     * before the YAML dump. {@code headerComment} must be non-null — use the 2-argument overload
     * for "no header at all" instead of passing {@code null} here.
     */
    public static void save(Path file, Map<String, Object> data, String headerComment) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(headerComment, "headerComment");
        writeAtomic(file, data, headerComment);
    }

    private static void writeAtomic(Path file, Map<String, Object> data, String headerComment) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmpFile = parent != null
            ? parent.resolve(file.getFileName().toString() + ".tmp")
            : Path.of(file.getFileName().toString() + ".tmp");

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        Yaml yaml = new Yaml(opts);

        try (Writer w = Files.newBufferedWriter(tmpFile, StandardCharsets.UTF_8)) {
            if (headerComment != null) w.write(headerComment);
            yaml.dump(data, w);
        }
        Files.move(tmpFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=ConfigWriterTest test`
Expected: PASS, 8 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/hu/patriksgit/paxapi/config/ConfigWriter.java src/test/java/hu/patriksgit/paxapi/config/ConfigWriterTest.java
git commit -m "feat(config): add ConfigWriter.save for atomic full-document write-back"
```

---

### Task 2: `ConfigWriter.upsertMissing` — idempotent missing-key backfill

**Files:**
- Modify: `src/main/java/hu/patriksgit/paxapi/config/ConfigWriter.java`
- Modify: `src/test/java/hu/patriksgit/paxapi/config/ConfigWriterTest.java`

**Interfaces:**
- Consumes: `ConfigWriter.save(Path, Map<String, Object>)` from Task 1 (called internally when there's something to persist).
- Produces: `ConfigWriter.upsertMissing(Path file, Map<String, Object> candidates, Logger logger) -> Set<String>` (throws `IOException`).

- [ ] **Step 1: Write the failing tests**

Append these imports to the top of `ConfigWriterTest.java` (alongside the existing ones from Task 1):

```java
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
```

And these static imports:

```java
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
```

Append these test methods inside the `ConfigWriterTest` class (before the final closing `}`):

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=ConfigWriterTest test`
Expected: compile error — `ConfigWriter.upsertMissing` does not exist yet.

- [ ] **Step 3: Write the implementation**

Add these imports to `ConfigWriter.java` (alongside the existing ones from Task 1):

```java
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
```

Add this method to the `ConfigWriter` class (after the two `save` overloads, before `writeAtomic`):

```java
    /**
     * Loads {@code file} if it exists (starts from an empty document if it doesn't — no
     * bundled-default extraction, unlike {@link ConfigFile#load}), then adds every key from
     * {@code candidates} that is NOT already present in the loaded document. Existing keys are
     * left untouched regardless of their value or shape — this is a presence-only decision
     * ({@code containsKey}, not a null-check: an existing key explicitly mapped to {@code null}
     * still counts as present). Persists via {@link #save(Path, Map)} only if something was
     * actually added; returns the added keys in {@code candidates}' iteration order.
     *
     * <p>{@code candidates} values must be non-null (not a supported input — no known caller
     * needs a null candidate value).
     *
     * @param logger used only to warn when {@code file} exists but parses to an empty document
     *               (mirrors {@link ConfigFile}'s identical handling of an empty {@code config.yml}).
     */
    public static Set<String> upsertMissing(Path file, Map<String, Object> candidates, Logger logger) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(logger, "logger");

        Map<String, Object> loaded = loadMutable(file, logger);
        Set<String> added = new LinkedHashSet<>();
        for (Map.Entry<String, Object> e : candidates.entrySet()) {
            if (!loaded.containsKey(e.getKey())) {
                loaded.put(e.getKey(), e.getValue());
                added.add(e.getKey());
            }
        }
        if (!added.isEmpty()) {
            save(file, loaded);
        }
        return added;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadMutable(Path file, Logger logger) throws IOException {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(file)) {
            Object parsed;
            try {
                parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            } catch (YAMLException e) {
                throw new ConfigException("Malformed YAML in " + file + " (see cause for details)", e);
            }
            if (parsed == null) {
                logger.warn("{} is empty — treating as an empty document for upsert.", file.getFileName());
                return new LinkedHashMap<>();
            }
            if (!(parsed instanceof Map)) {
                throw new ConfigException("Config file root must be a YAML mapping: " + file);
            }
            return new LinkedHashMap<>((Map<String, Object>) parsed);
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=ConfigWriterTest test`
Expected: PASS, 20 tests green (8 from Task 1 + 12 from this task).

- [ ] **Step 5: Run the full test suite**

Run: `mvn -q test`
Expected: PASS, all tests green (no regressions elsewhere).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/hu/patriksgit/paxapi/config/ConfigWriter.java src/test/java/hu/patriksgit/paxapi/config/ConfigWriterTest.java
git commit -m "feat(config): add ConfigWriter.upsertMissing for idempotent key backfill"
```

---

### Task 3: Document `ConfigWriter` in `API.md`

**Files:**
- Modify: `API.md`

**Interfaces:**
- Consumes: the final `ConfigWriter` API from Tasks 1–2 (`save`, `save` with header, `upsertMissing`).
- Produces: nothing consumed by later tasks (this is the last task in the plan).

- [ ] **Step 1: Remove the stale "no write-back" claim from the Config module's own "Mit NEM tud"**

Find this exact block in the `## Config modul` section:
```
### Mit NEM tud
- **Nincs write-back** — csak olvas, nem tud visszaírni a fájlba
- **Nincs config-migráció** — ha egy plugin frissítéskor egy új kötelező kulcsot vár, egy régi, hiányos configon a `require(...)` hívás elhal induláskor, amíg valaki manuálisan nem frissíti a fájlt
- Nincs `Set`/`byte[]`/`Date` (YAML `!!set`/`!!binary`/`!!timestamp`) natívan visszaadó getter — ezek elméletileg átjutnának a mély-immutabilizáción felöltetlenül, de jelenleg nincs public getter, ami ezeket kiadná
```
Replace with:
```
### Mit NEM tud
- **Nincs config-migráció** — ha egy plugin frissítéskor egy új kötelező kulcsot vár, egy régi, hiányos configon a `require(...)` hívás elhal induláskor, amíg valaki manuálisan nem frissíti a fájlt
- Nincs `Set`/`byte[]`/`Date` (YAML `!!set`/`!!binary`/`!!timestamp`) natívan visszaadó getter — ezek elméletileg átjutnának a mély-immutabilizáción felöltetlenül, de jelenleg nincs public getter, ami ezeket kiadná
- `ConfigFile` maga továbbra is csak olvas — az írás a külön `ConfigWriter` osztály dolga (lásd lentebb), a human-edited `config.yml` in-place, comment-preserving szerkesztése pedig szándékosan nincs támogatva
```

- [ ] **Step 2: Insert the new `ConfigWriter` subsection**

Find this exact line (the end of the Config module's content, right before the divider to the next module):
```
### `ConfigException`

`IOException` leszármazott — kötelező kulcs hiánya vagy hibás YAML esetén dobódik (`require()`, vagy magából a `load()`-ból malformed YAML esetén).

---
```
Replace with (note the outer fence below uses four backticks specifically because the content itself contains a ```java fence — copy everything between the outer ```` markers, not including the ```` markers themselves):

````markdown
### `ConfigException`

`IOException` leszármazott — kötelező kulcs hiánya vagy hibás YAML esetén dobódik (`require()`, vagy magából a `load()`-ból malformed YAML esetén).

### `ConfigWriter` — write-back machine-managed side-file-okhoz

Külön osztály `ConfigFile` mellett (nem azon), mert `ConfigFile`-nak immutable-nek KELL maradnia. A human-edited `config.yml` in-place, comment-preserving szerkesztése szándékosan NINCS támogatva (egy YAML dump csendben eldobná az összes kommentet) — a `ConfigWriter` mindig egy külön, gépileg kezelt side-fájlba ír (pl. `auth-spawn.yml`, `worlds.yml`), sosem magába a `config.yml`-be.

```java
// Teljes felülírás — a hívó adja a KOMPLETT kívánt dokumentumot:
Map<String, Object> data = new LinkedHashMap<>();
data.put("world", "spawn_world");
data.put("x", 100.5);
ConfigWriter.save(dataDir.resolve("auth-spawn.yml"), data);

// Fejléc-kommenttel:
ConfigWriter.save(dataDir.resolve("maintenance.yml"), data, "# Ezt a fájlt a plugin automatikusan írja.\n");

// Idempotens hiányzó-kulcs-pótlás — csak az ÚJ kulcsokat adja hozzá, a meglévőket érintetlenül hagyja:
Map<String, Object> discovered = new LinkedHashMap<>();
for (World w : Bukkit.getWorlds()) {
    Map<String, Object> defaults = new LinkedHashMap<>();
    defaults.put("enderpearl-cooldown", 16);
    defaults.put("totems-enabled", true);
    discovered.put(w.getName(), defaults);
}
Set<String> added = ConfigWriter.upsertMissing(dataDir.resolve("worlds.yml"), discovered, logger);
if (!added.isEmpty()) {
    sender.sendMessage("Új világ(ok) hozzáadva: " + String.join(", ", added));
}
```

- `save(file, data)` — **teljes felülírás**, nem részleges merge. Atomikus (`.tmp` + `ATOMIC_MOVE` + `REPLACE_EXISTING`), létrehozza a szülő könyvtárat, ha hiányzik.
- `save(file, data, headerComment)` — ugyanaz, plusz `headerComment` nyers szövegként a YAML dump elé kerül. `headerComment` nem lehet `null` — ha nem kell fejléc, a 2-argumentumos overloadot használd.
- `upsertMissing(file, candidates, logger)` — betölti a fájlt (ha nincs, üresről indul), a `candidates` minden kulcsát, ami MÉG NINCS jelen, hozzáadja; a meglévő kulcsokat — akár `null` értékűek is — érintetlenül hagyja. Csak akkor ír fájlt, ha tényleg volt hozzáadás. Visszaadja a ténylegesen hozzáadott kulcsok halmazát (megőrzött sorrenddel).

### Mit NEM tud (ConfigWriter)
- Nincs comment-preserving in-place `config.yml` szerkesztés — mindig egy külön, gépileg kezelt side-fájlba ír
- `upsertMissing` nem támogat section-path/dot-notation-t — csak gyökér-szintű kulcsokkal dolgozik
- `upsertMissing` `candidates`-ének értékei nem lehetnek `null`
- Nincs beépített concurrency-védelem — párhuzamos írók esetén a hívónak kell szinkronizálnia

---
````

- [ ] **Step 3: Fix the stale Config bullet in the final summary section**

Find this exact line in the `## Összefoglaló: mit NEM tud a PAxAPI` section:
```
- **Config**: nincs write-back (csak olvasás); nincs migráció régi configokhoz (egy új `require()`-elt kulcs egy régi configon induláskor elhal, amíg valaki manuálisan nem frissíti)
```
Replace with:
```
- **Config**: `ConfigFile` maga csak olvas — az írás a `ConfigWriter` osztály dolga (teljes felülírás + idempotens hiányzó-kulcs-pótlás, mindig egy külön gépi side-fájlba, sosem a human-edited config.yml-be); nincs migráció régi configokhoz (egy új `require()`-elt kulcs egy régi configon induláskor elhal, amíg valaki manuálisan nem frissíti); nincs comment-preserving in-place config.yml szerkesztés
```

- [ ] **Step 4: Verify the doc renders sanely**

Run: `grep -n "^## \|^### " API.md`
Expected: `### ConfigWriter` (or similarly named heading) appears right after `### ConfigException` and before `## Command modul`; no doubled or missing `---` dividers; no stray four-backtick markers left in the file (`grep -n '````' API.md` should return zero matches).

- [ ] **Step 5: Commit**

```bash
git add API.md
git commit -m "docs(config): document ConfigWriter, fix stale Config write-back claim"
```
