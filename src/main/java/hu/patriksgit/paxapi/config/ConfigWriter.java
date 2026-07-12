package hu.patriksgit.paxapi.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
                LoaderOptions opts = new LoaderOptions();
                opts.setAllowDuplicateKeys(false);
                parsed = new Yaml(new SafeConstructor(opts)).load(in);
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
