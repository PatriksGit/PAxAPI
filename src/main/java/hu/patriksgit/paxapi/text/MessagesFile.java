package hu.patriksgit.paxapi.text;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * YAML-alapú üzenetkezelés Paper / Velocity pluginokhoz.
 *
 * <p>Egyetlen belépési pont: betöltés, kulcs-keresés, {@code %prefix%} helyettesítés,
 * multi-line YAML lista, {@link #get}/{@link #send}/{@link #sendTitle}/{@link #sendActionBar}
 * — plugin kódban nem kell szöveg-formázás.
 *
 * <p>Formattálást a {@link TextUtil} végzi (legacy &amp;-kódok, hex, MiniMessage).
 *
 * <p>Thread-safe: {@link #reload()} új snapshot-ot publikál atomikusan egy volatile
 * write-tal, concurrent {@link #get}/{@link #send} hívások mindig konzisztens állapotot látnak.
 */
public final class MessagesFile {

    private record Snapshot(
            Map<String, String> singles,
            Map<String, List<String>> lists,
            String yamlPrefix,
            Map<String, String> namedPrefixes,
            Map<String, Component> namedPrefixComponents) {}

    private final Path file;
    private final Logger logger;
    /** null = prefix from YAML {@code prefix:} key. */
    private final Supplier<String> prefixSupplier;
    private volatile Snapshot snapshot;
    private final Set<String> warned = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private MessagesFile(Path file, Logger logger, Supplier<String> prefixSupplier, Snapshot snapshot) {
        this.file = file;
        this.logger = logger;
        this.prefixSupplier = prefixSupplier;
        this.snapshot = snapshot;
    }

    /**
     * Loads (and auto-extracts if missing) a YAML messages file.
     * Prefix is read from the {@code prefix:} key inside the file.
     *
     * @param file            full path to the messages.yml
     * @param defaultResource bundled fallback resource copied if {@code file} doesn't exist; may be {@code null}
     * @param logger          SLF4J logger for WARN-once missing-key messages
     */
    public static MessagesFile load(Path file, InputStream defaultResource, Logger logger) throws IOException {
        return load(file, defaultResource, logger, null);
    }

    /**
     * Loads with an external prefix supplier. {@code prefixSupplier.get()} is called on
     * every {@link #get}/{@link #send} instead of reading the {@code prefix:} YAML key.
     * Use this when one file inherits the prefix from another (e.g. maintenance module).
     */
    public static MessagesFile load(Path file, InputStream defaultResource, Logger logger,
                                    Supplier<String> prefixSupplier) throws IOException {
        ensureExists(file, defaultResource);
        return new MessagesFile(file, logger, prefixSupplier, buildSnapshot(file, logger));
    }

    private static void ensureExists(Path file, InputStream defaultResource) throws IOException {
        try (InputStream in = defaultResource) {
            if (Files.exists(file)) return;
            if (in == null) throw new IOException("File does not exist and no default provided: " + file);
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            try {
                Files.copy(in, file);
            } catch (FileAlreadyExistsException ignored) {
                // another thread/process created the file concurrently — that's fine
            }
        }
    }

    /** Reloads from disk. Build new snapshot first, then atomically replace (safe even if build fails). */
    public void reload() throws IOException {
        Snapshot fresh = buildSnapshot(file, logger);
        this.warned.clear();
        this.snapshot = fresh;
    }

    /** Clears the warn-once tracking cache without reloading the file. */
    public void clearWarnCache() {
        warned.clear();
    }

    // ── snapshot build ────────────────────────────────────────────────────────

    private static Snapshot buildSnapshot(Path file, Logger logger) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            Object root;
            try {
                LoaderOptions opts = new LoaderOptions();
                opts.setAllowDuplicateKeys(false);
                root = new Yaml(new SafeConstructor(opts)).load(in);
            } catch (YAMLException e) {
                throw new IOException("Failed to parse " + file.getFileName() + ": " + e.getMessage(), e);
            }
            if (root == null) {
                logger.warn("{} is empty — using empty message map. Delete to regenerate the default.",
                        file.getFileName());
                return new Snapshot(Map.of(), Map.of(), "", Map.of(), Map.of());
            }
            if (!(root instanceof Map)) {
                throw new IOException(file.getFileName() + " root must be a YAML mapping, not "
                        + root.getClass().getSimpleName());
            }
            Map<String, String> singles = new HashMap<>();
            Map<String, List<String>> lists = new HashMap<>();
            flatten("", root, singles, lists, logger, file.getFileName().toString());
            String prefix = singles.getOrDefault("prefix", "");
            Map<String, String> namedPrefixes = new HashMap<>();
            for (Map.Entry<String, String> e : singles.entrySet()) {
                if (e.getKey().startsWith("prefix.")) {
                    namedPrefixes.put(e.getKey().substring("prefix.".length()), e.getValue());
                }
            }
            // Pre-parsed once per reload, not per get()/send() call — named prefixes only
            // change on reload, so re-running TextUtil.parse per chat message would be wasted work.
            Map<String, Component> namedPrefixComponents = new HashMap<>(namedPrefixes.size());
            namedPrefixes.forEach((name, raw) -> namedPrefixComponents.put(name + "-prefix", TextUtil.parse(raw)));
            if (!namedPrefixes.isEmpty()) {
                warnUnmatchedPrefixTokens(singles, namedPrefixes, logger, file.getFileName().toString());
            }
            return new Snapshot(
                    Collections.unmodifiableMap(singles),
                    Collections.unmodifiableMap(lists),
                    prefix,
                    Collections.unmodifiableMap(namedPrefixes),
                    Collections.unmodifiableMap(namedPrefixComponents));
        }
    }

    // Name class must match TextUtil.PLACEHOLDER's ([A-Za-z0-9_-]+) — a section name may itself
    // contain hyphens (e.g. "staff-chat"), and this must still recognize %staff-chat-prefix% as
    // a prefix token, or a typo in a hyphenated name would silently go undetected.
    private static final java.util.regex.Pattern PREFIX_TOKEN =
            java.util.regex.Pattern.compile("%([A-Za-z0-9_-]+)-prefix%");

    /**
     * WARNs (once per unique name, per load/reload) about a {@code %name-prefix%} token that
     * appears in some message but has no corresponding {@code prefix.name} entry — almost
     * certainly a typo or a forgotten config entry, since this suffix convention belongs
     * exclusively to the named-prefix feature. Only called when at least one named prefix is
     * actually defined, so a messages.yml not using this feature at all is never flagged for a
     * coincidentally "-prefix"-suffixed placeholder of its own.
     */
    private static void warnUnmatchedPrefixTokens(Map<String, String> singles, Map<String, String> namedPrefixes,
                                                    Logger logger, String fileName) {
        Set<String> warnedNames = new java.util.HashSet<>();
        for (String value : singles.values()) {
            java.util.regex.Matcher m = PREFIX_TOKEN.matcher(value);
            while (m.find()) {
                String name = m.group(1);
                if (!namedPrefixes.containsKey(name) && warnedNames.add(name)) {
                    logger.warn("{}: found '%{}-prefix%' but no matching 'prefix.{}' entry — "
                            + "typo, or a forgotten prefix section? The token will stay literal.",
                            fileName, name, name);
                }
            }
        }
    }

    private static void flatten(String key, Object value,
                                Map<String, String> singles, Map<String, List<String>> lists,
                                Logger logger, String fileName) {
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String child = String.valueOf(e.getKey());
                String full  = key.isEmpty() ? child : key + "." + child;
                flatten(full, e.getValue(), singles, lists, logger, fileName);
            }
        } else if (value instanceof List<?> l) {
            List<String> rows = new ArrayList<>(l.size());
            for (Object item : l) {
                if (item instanceof Map || item instanceof List) {
                    logger.warn("{}: list key '{}' contains a nested structure — item skipped.", fileName, key);
                } else {
                    rows.add(item == null ? "" : String.valueOf(item));
                }
            }
            List<String> immutable = Collections.unmodifiableList(rows);
            lists.put(key, immutable);
            // Also store \n-joined in singles so get() returns a multi-line Component
            // for kick/disconnect screens, where a single Component is required.
            singles.put(key, String.join("\n", immutable));
        } else if (value == null) {
            if (!key.isEmpty()) {
                logger.warn("{}: key '{}' has no value — using empty string.", fileName, key);
                singles.put(key, "");
            }
        } else {
            singles.put(key, String.valueOf(value));
        }
    }

    // ── prefix helper ─────────────────────────────────────────────────────────

    private String prefix(Snapshot s) {
        String p = prefixSupplier != null ? prefixSupplier.get() : s.yamlPrefix();
        return p != null ? p : "";
    }

    /** Raw prefix string (before TextUtil parsing). */
    public String rawPrefix() {
        Snapshot s = snapshot;
        return prefix(s);
    }

    /**
     * Raw named-prefix string for {@code name} (before TextUtil parsing) — the value of
     * {@code prefix.<name>} when the YAML {@code prefix:} key is authored as a map, e.g.
     * <pre>{@code
     * prefix:
     *   helpop: "&8[HelpOp]&r "
     *   staffchat: "&8[SC]&r "
     * }</pre>
     * Returns {@code ""} if {@code name} has no entry. Unrelated to {@link #rawPrefix()} /
     * the legacy flat {@code %prefix%} token, which only applies when {@code prefix:} is a
     * plain string.
     *
     * <p>Every named prefix is also auto-substituted (as a colored Component, not a literal
     * string) wherever its {@code %<name>-prefix%} token appears in any {@link #get}/{@link #send}/
     * {@link #broadcast}/{@link #formatChat}/{@link #getList} template — callers don't need to
     * pass it through their own placeholders map.
     */
    public String prefix(String name) {
        Objects.requireNonNull(name, "name");
        return snapshot.namedPrefixes().getOrDefault(name, "");
    }

    /** {@code <name>-prefix -> Component} for every named prefix, pre-parsed at snapshot build time. */
    private static Map<String, Component> namedPrefixComponents(Snapshot s) {
        return s.namedPrefixComponents();
    }

    // ── get() ─────────────────────────────────────────────────────────────────

    /**
     * Component for {@code key}. List-typed keys are joined with
     * {@link Component#newline()} — suitable for kick / disconnect screens.
     * Missing key → empty Component + WARN-once.
     */
    public Component get(String key) {
        return get(key, null);
    }

    /**
     * Component with placeholder substitution. Missing key → empty + WARN-once.
     *
     * <p><b>Note:</b> the registered {@link TextUtil} {@code PlaceholderExpander}
     * (PlaceholderAPI / MiniPlaceholders bridge) is <em>not</em> applied here — only
     * literal {@code %key%} map placeholders and MiniMessage are processed. Callers that
     * need expander/PAPI resolution should use {@code TextUtil}'s {@code send*} /
     * {@code sendChat} methods with an {@code Audience}.
     */
    public Component get(String key, Map<String, String> placeholders) {
        Objects.requireNonNull(key, "key");
        Snapshot s = snapshot;
        String value = s.singles().get(key);
        if (value == null) {
            warnMissing(key);
            return Component.empty();
        }
        String resolved = value.replace("%prefix%", prefix(s));
        Map<String, Component> namedPrefixes = namedPrefixComponents(s);
        if (resolved.contains("\n")) {
            String[] lines = resolved.split("\n", -1);
            Component result = TextUtil.parse(lines[0], placeholders, namedPrefixes);
            for (int i = 1; i < lines.length; i++) {
                result = result.append(Component.newline()).append(TextUtil.parse(lines[i], placeholders, namedPrefixes));
            }
            return result;
        }
        return TextUtil.parse(resolved, placeholders, namedPrefixes);
    }

    // ── getList() ─────────────────────────────────────────────────────────────

    /**
     * Line-by-line Components for a YAML list key (e.g. {@code admin.help}).
     * Defensive: string key → 1-element list + WARN-once. Missing → empty list + WARN-once.
     */
    public List<Component> getList(String key) {
        return getList(key, null);
    }

    /** Line-by-line Components with placeholder substitution. */
    public List<Component> getList(String key, Map<String, String> placeholders) {
        Objects.requireNonNull(key, "key");
        Snapshot s = snapshot;
        Map<String, Component> namedPrefixes = namedPrefixComponents(s);
        List<String> rawList = s.lists().get(key);
        if (rawList != null) {
            String p = prefix(s);
            List<Component> out = new ArrayList<>(rawList.size());
            for (String line : rawList) out.add(TextUtil.parse(line.replace("%prefix%", p), placeholders, namedPrefixes));
            return Collections.unmodifiableList(out);
        }
        String single = s.singles().get(key);
        if (single != null) {
            if (warned.add(key)) {
                logger.warn("messages.yml key '{}' is a string but used as a list — wrapping. "
                        + "Convert to YAML list (- 'line') to silence this.", key);
            }
            return List.of(TextUtil.parse(single.replace("%prefix%", prefix(s)), placeholders, namedPrefixes));
        }
        warnMissing(key);
        return Collections.emptyList();
    }

    // ── send() ────────────────────────────────────────────────────────────────

    /** Sends — handles both string and list YAML values automatically. */
    public void send(Audience target, String key) {
        send(target, key, null);
    }

    /** Sends with placeholders — handles both string and list YAML values. */
    public void send(Audience target, String key, Map<String, String> placeholders) {
        Objects.requireNonNull(key, "key");
        Snapshot s = snapshot;
        String p = prefix(s);
        Map<String, Component> namedPrefixes = namedPrefixComponents(s);
        List<String> rawList = s.lists().get(key);
        if (rawList != null) {
            List<String> resolved = new ArrayList<>(rawList.size());
            for (String line : rawList) resolved.add(line.replace("%prefix%", p));
            TextUtil.sendChat(target, resolved, placeholders, namedPrefixes);
            return;
        }
        String raw = s.singles().get(key);
        if (raw == null) { warnMissing(key); return; }
        TextUtil.sendChat(target, raw.replace("%prefix%", p), placeholders, namedPrefixes);
    }

    // ── broadcast() ──────────────────────────────────────────────────────────

    /** Sends {@code key} to every audience in {@code targets}. Null targets is a no-op. */
    public void broadcast(Iterable<? extends Audience> targets, String key) {
        broadcast(targets, key, null);
    }

    /** Sends {@code key} with placeholders to every audience in {@code targets}. */
    public void broadcast(Iterable<? extends Audience> targets, String key, Map<String, String> placeholders) {
        Objects.requireNonNull(key, "key");
        if (targets == null) return;
        for (Audience target : targets) send(target, key, placeholders);
    }

    // ── formatChat() ─────────────────────────────────────────────────────────

    /**
     * Resolves a YAML key and formats it with Component-valued placeholders via
     * {@link TextUtil#formatChat(String, Map)}. {@code %prefix%} is substituted
     * from the YAML {@code prefix:} key (or the external prefix supplier) before
     * the component substitution runs.
     *
     * <p><b>Note:</b> the registered {@link TextUtil} {@code PlaceholderExpander}
     * (PlaceholderAPI / MiniPlaceholders bridge) is <em>not</em> applied here — only
     * literal {@code %key%} component placeholders and MiniMessage are processed. Callers
     * that need expander/PAPI resolution should use {@code TextUtil}'s {@code send*} /
     * {@code sendChat} methods with an {@code Audience}.
     */
    public Component formatChat(String key, Map<String, Component> components) {
        Objects.requireNonNull(key, "key");
        Snapshot s = snapshot;
        String raw = s.singles().get(key);
        if (raw == null) { warnMissing(key); return Component.empty(); }
        String resolved = raw.replace("%prefix%", prefix(s));
        Map<String, Component> merged = namedPrefixComponents(s);
        if (components != null && !components.isEmpty()) {
            if (merged.isEmpty()) {
                merged = components;
            } else {
                merged = new HashMap<>(merged);
                merged.putAll(components);
            }
        }
        return TextUtil.formatChat(resolved, merged);
    }

    // ── sendTitle() ───────────────────────────────────────────────────────────

    /**
     * Sends a title from a YAML list key. Format matches {@link TextUtil#sendTitle}:
     * {@code lines[0]} = title, {@code lines[1]} = subtitle, {@code lines[2]} = timing ticks.
     */
    public void sendTitle(Audience target, String key) {
        sendTitle(target, key, null);
    }

    public void sendTitle(Audience target, String key, Map<String, String> placeholders) {
        Snapshot s = snapshot;
        List<String> resolved = resolvedList(s, key);
        if (resolved.isEmpty()) return;
        TextUtil.sendTitle(target, resolved, placeholders, namedPrefixComponents(s));
    }

    // ── sendActionBar() ───────────────────────────────────────────────────────

    public void sendActionBar(Audience target, String key) {
        sendActionBar(target, key, null);
    }

    public void sendActionBar(Audience target, String key, Map<String, String> placeholders) {
        Snapshot s = snapshot;
        List<String> resolved = resolvedList(s, key);
        if (resolved.isEmpty()) return;
        TextUtil.sendActionBar(target, resolved, placeholders, namedPrefixComponents(s));
    }

    // ── internal helpers ──────────────────────────────────────────────────────

    /**
     * Raw strings with {@code %prefix%} substituted — used by sendTitle/sendActionBar
     * which need raw strings so TextUtil can apply its PlaceholderExpander. Takes the
     * caller's already-read {@code Snapshot} so it's consistent with the caller's own
     * subsequent use of that snapshot (e.g. for named-prefix Components) even if a
     * concurrent {@link #reload()} swaps the volatile field in between.
     */
    private List<String> resolvedList(Snapshot s, String key) {
        Objects.requireNonNull(key, "key");
        String p = prefix(s);
        List<String> rawList = s.lists().get(key);
        if (rawList != null) {
            List<String> out = new ArrayList<>(rawList.size());
            for (String line : rawList) out.add(line.replace("%prefix%", p));
            return out;
        }
        String single = s.singles().get(key);
        if (single != null) return List.of(single.replace("%prefix%", p));
        warnMissing(key);
        return Collections.emptyList();
    }

    private void warnMissing(String key) {
        if (warned.add(key)) {
            logger.warn("Missing messages.yml key: '{}' — empty message will be sent. "
                    + "Please update your messages.yml.", key);
        }
    }
}
