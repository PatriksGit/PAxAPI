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
