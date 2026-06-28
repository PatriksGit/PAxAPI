package hu.patriksgit.paxapi.text;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Egyetlen belépési pont szöveg-formázáshoz Paper / Velocity pluginok között.
 *
 * <p>Támogatja:
 * <ul>
 *   <li>Legacy ampersand kódok ({@code &a}–{@code &f}, {@code &k}–{@code &o}, {@code &r})</li>
 *   <li>Section-sign kódok ({@code §a}–{@code §f} stb.) — PlaceholderAPI alapértelmezett kimenet</li>
 *   <li>Hex színek ({@code &#RRGGBB} és {@code <#RRGGBB>})</li>
 *   <li>MiniMessage tagek ({@code <gradient>}, {@code <rainbow>}, {@code <hover>}, stb.)</li>
 *   <li>Placeholder helyettesítés ({@code %key%} → Map érték), injection-safe</li>
 *   <li>Külső placeholder rendszer via {@link PlaceholderExpander} (PAPI / MiniPlaceholders)</li>
 * </ul>
 *
 * <p>Színkódok ({@code &0}–{@code &f}, {@code &#RRGGBB}) implicit resetelnek minden
 * aktív formázást (bold, italic stb.) — ez megfelel a Legacy Minecraft viselkedésnek.
 * Formázó kódok ({@code &l}, {@code &o}, stb.) NEM resetelnek.
 */
public final class TextUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_-]+)%");

    private static final String LEGACY_CODES = "0123456789abcdefklmnor";
    private static final String HEX_CHARS    = "0123456789abcdefABCDEF";

    /**
     * MiniMessage tag names that must not be used as placeholder keys.
     * A placeholder key whose lowercase form matches any of these is silently skipped
     * so the built-in tag keeps working (e.g. {@code <reset>} injected by convertLegacy).
     */
    private static final java.util.Set<String> RESERVED_TAGS = java.util.Set.of(
            "reset", "bold", "b", "italic", "i", "underlined", "u",
            "strikethrough", "st", "obfuscated", "obf",
            "click", "hover", "color", "colour", "c",
            "gradient", "rainbow", "key", "lang", "insert",
            "newline", "br", "score", "selector", "nbt",
            "font", "transition", "pride", "shadow"
    );

    private record ExpanderConfig(PlaceholderExpander expander, boolean trusted) {}

    private static volatile ExpanderConfig expanderConfig = new ExpanderConfig(null, false);

    /** Optional diagnostic logger; when set, MiniMessage parse failures in safeDeserialize are logged at WARN. */
    private static volatile org.slf4j.Logger debugLogger = null;

    private TextUtil() {}

    /**
     * Sets an optional diagnostic logger. When set, MiniMessage parse failures in
     * {@link #safeDeserialize} are logged at WARN. Pass {@code null} to disable.
     */
    public static void setDebugLogger(org.slf4j.Logger logger) {
        debugLogger = logger;
    }

    /**
     * Clears the global expander and debug logger. Call from your plugin's onDisable() if
     * TextUtilAPI is a shared/provided library, to avoid leaking the old plugin's classloader
     * across reloads.
     */
    public static void reset() {
        expanderConfig = new ExpanderConfig(null, false);
        debugLogger = null;
    }

    // ── PlaceholderExpander ──────────────────────────────────────────────────

    /**
     * Registers an external placeholder expander (e.g. PlaceholderAPI / MiniPlaceholders).
     *
     * <p>By default the expander's output is MiniMessage-escaped so resolved placeholders
     * cannot inject {@code <click>}, {@code <hover>} or other interactive tags.
     * Legacy {@code &}/{@code §} color codes still render because they are converted
     * <em>after</em> escaping. Use {@link #setExpander(PlaceholderExpander, boolean)} with
     * {@code trustOutput = true} only when the expander output is fully trusted and you want
     * it parsed as MiniMessage. Resets the trust flag to {@code false}.
     *
     * <p><b>Note:</b> this is a process-global, last-writer-wins setting.
     */
    public static void setExpander(PlaceholderExpander e) {
        expanderConfig = new ExpanderConfig(e, false);
    }

    /**
     * Registers an external placeholder expander with explicit trust control.
     *
     * @param e           the expander; {@code null} disables expansion
     * @param trustOutput {@code true} to parse the expander's output as MiniMessage;
     *                    {@code false} (recommended default) to escape MiniMessage tags
     *                    in the expander output so untrusted placeholders cannot inject
     *                    interactive components
     *
     * <p><b>Note:</b> this is a process-global, last-writer-wins setting.
     */
    public static void setExpander(PlaceholderExpander e, boolean trustOutput) {
        expanderConfig = new ExpanderConfig(e, trustOutput);
    }

    // ── parse() ──────────────────────────────────────────────────────────────

    /** Szöveg → Component, placeholderek nélkül. */
    public static Component parse(String text) {
        return parse(text, null);
    }

    /**
     * Szöveg → Component placeholderekkel.
     * A {@code placeholders} értékei injection-safe módon kerülnek be
     * (a value-ban szereplő MiniMessage tagek literálisan jelennek meg).
     *
     * <p>A kulcsok belső tag-névvé kisbetűsödnek, ezért a csak kis/nagybetűben
     * eltérő kulcsok ({@code "Name"} vs {@code "name"}) ütköznek — kerüld őket.
     *
     * <p>Rosszul formázott MiniMessage markup (pl. {@code <gradient:#zzz>}) nem dob
     * kivételt — ehelyett plain-text fallbackre esik vissza.
     *
     * <p>A {@code RESERVED_TAGS}-ban szereplő kulcsok ({@code reset}, {@code bold} stb.)
     * nem regisztrálódnak placeholder-ként, így a beépített MiniMessage tag-ek nem
     * árnyékolhatók el. A foglalt kulcsú {@code %key%} tokenek szó szerint maradnak.
     */
    public static Component parse(String text, Map<String, String> placeholders) {
        if (text == null) return Component.empty();

        String converted = convertLegacy(text);

        TagResolver resolver = TagResolver.empty();
        if (placeholders != null && !placeholders.isEmpty()) {
            List<TagResolver> resolvers = new ArrayList<>(placeholders.size());
            Set<String> seen = new HashSet<>();
            StringBuilder sb = new StringBuilder(converted.length());
            Matcher m = PLACEHOLDER.matcher(converted);
            while (m.find()) {
                String key = m.group(1);
                String value = placeholders.get(key);
                if (value != null) {
                    String lowerKey = key.toLowerCase(Locale.ROOT);
                    if (RESERVED_TAGS.contains(lowerKey)) {
                        // Reserved MiniMessage tag name — skip to preserve built-in behaviour
                        m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                    } else {
                        if (seen.add(lowerKey)) {
                            resolvers.add(Placeholder.unparsed(lowerKey, value));
                        }
                        m.appendReplacement(sb, Matcher.quoteReplacement("<" + lowerKey + ">"));
                    }
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                }
            }
            m.appendTail(sb);
            converted = sb.toString();
            if (!resolvers.isEmpty()) {
                resolver = TagResolver.resolver(resolvers);
            }
        }

        return safeDeserialize(converted, resolver, text);
    }

    /**
     * Mint {@link #parse(String)}, de a bemenetet megbízhatatlannak (pl. player input) kezeli:
     * a nyers MiniMessage tag szintaxist ({@code <click>}, {@code <hover>}, {@code <run_command>}
     * stb.) escape-eli, MIELŐTT a legacy {@code &}/{@code §} kódokat színné/formázássá alakítaná.
     * Így a hívó fél csak szín-/formázókódokat (pl. {@code &5}, {@code &l}, {@code &#RRGGBB}) tud
     * érvényesíteni a szövegében — interaktív vagy egyéb MiniMessage tageket nem tud becsempészni,
     * azok literális szövegként jelennek meg.
     *
     * <p>Nem kezel {@code %key%} placeholdereket (a bemenet a teljes, megjelenítendő szöveg) —
     * ha az eredményt egy placeholderes sablonba kell illeszteni, használd {@link #formatChat}
     * Component-alapú placeholderét.
     */
    public static Component parseLegacyOnly(String text) {
        if (text == null) return Component.empty();
        String escaped = MINI.escapeTags(text);
        return safeDeserialize(convertLegacy(escaped), TagResolver.empty(), text);
    }

    // ── strip() ──────────────────────────────────────────────────────────────

    /**
     * Strips formatting and returns plain text.
     *
     * <p>Legacy {@code &}-codes, hex colors, and MiniMessage tags are all stripped.
     * However, unresolved {@code %key%} placeholder tokens are <em>not</em> removed:
     * {@code strip} calls {@link #parse(String)} with no placeholder map, so any
     * {@code %key%} token has no resolver and survives literally in the output.
     * If placeholder token removal is required, resolve them first by calling
     * {@link #parse(String, Map)} with a placeholder map.
     */
    public static String strip(String text) {
        if (text == null) return "";
        return PlainTextComponentSerializer.plainText().serialize(parse(text));
    }

    // ── builder() ────────────────────────────────────────────────────────────

    /**
     * Returns a fluent {@link ComponentBuilder} for constructing interactive
     * (hoverable / clickable) Components with injection-safe placeholder support.
     *
     * <pre>{@code
     * Component msg = TextUtil.builder("&7%sender% &8→ &7%receiver%")
     *     .placeholder("sender", senderName)
     *     .placeholder("receiver", targetName)
     *     .hover("&7Kattints a válaszhoz!")
     *     .suggest("/msg " + senderName + " ")
     *     .build();
     * }</pre>
     */
    public static ComponentBuilder builder(String text) {
        return new ComponentBuilder(text);
    }

    // ── formatChat() ─────────────────────────────────────────────────────────

    /**
     * Formats a chat line by substituting {@code %key%} tokens with pre-built Components.
     *
     * <p>Use this when you need Component-valued placeholders (e.g. a player's display name
     * with hover, or the chat message Component from Paper's {@code AsyncChatEvent}):
     * <pre>{@code
     * Component line = TextUtil.formatChat(
     *     "%prefix% %name% &8» &f%message%",
     *     Map.of(
     *         "prefix",  TextUtil.parse(prefix),
     *         "name",    Component.text(player.getName()),
     *         "message", event.message()
     *     )
     * );
     * }</pre>
     *
     * <p>Map lookup uses the original case from the format string (same contract as
     * {@link #parse(String, Map)}). The MiniMessage tag name is lowercased internally.
     * Component values render as-is — caller is responsible for escaping untrusted names
     * (use {@link Component#text(String)} for raw player input).
     */
    public static Component formatChat(String format, Map<String, Component> components) {
        if (format == null) return Component.empty();
        String converted = convertLegacy(format);
        if (components == null || components.isEmpty()) return safeDeserialize(converted, TagResolver.empty(), format);
        List<TagResolver> resolvers = new ArrayList<>(components.size());
        Set<String> seen = new HashSet<>();
        StringBuilder sb = new StringBuilder(converted.length());
        Matcher m = PLACEHOLDER.matcher(converted);
        while (m.find()) {
            String key = m.group(1);
            Component comp = components.get(key);
            if (comp != null) {
                String lowerKey = key.toLowerCase(Locale.ROOT);
                if (seen.add(lowerKey)) {
                    resolvers.add(Placeholder.component(lowerKey, comp));
                }
                m.appendReplacement(sb, Matcher.quoteReplacement("<" + lowerKey + ">"));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        String result = sb.toString();
        return safeDeserialize(result,
                resolvers.isEmpty() ? TagResolver.empty() : TagResolver.resolver(resolvers),
                format);
    }

    // ── send() — visszafelé kompatibilis overloadok ──────────────────────────

    public static void send(Audience target, String text) {
        sendChat(target, text, null);
    }

    public static void send(Audience target, String text, Map<String, String> placeholders) {
        sendChat(target, text, placeholders);
    }

    public static void send(Audience target, List<String> lines) {
        sendChat(target, lines, null);
    }

    public static void send(Audience target, List<String> lines, Map<String, String> placeholders) {
        sendChat(target, lines, placeholders);
    }

    // ── sendChat() ───────────────────────────────────────────────────────────

    /** Minden sor külön chat-üzenetként küldve. */
    public static void sendChat(Audience target, String text) {
        sendChat(target, text, null);
    }

    /** Minden sor külön chat-üzenetként küldve, placeholderekkel. */
    public static void sendChat(Audience target, String text, Map<String, String> placeholders) {
        Objects.requireNonNull(target, "target");
        if (text != null && text.contains("\n")) {
            for (String line : text.split("\n", -1)) {
                target.sendMessage(parse(applyExpander(line, target), placeholders));
            }
        } else {
            target.sendMessage(parse(applyExpander(text, target), placeholders));
        }
    }

    /** Minden sor külön chat-üzenetként küldve. */
    public static void sendChat(Audience target, List<String> lines) {
        sendChat(target, lines, null);
    }

    /** Minden sor külön chat-üzenetként küldve, placeholderekkel. */
    public static void sendChat(Audience target, List<String> lines, Map<String, String> placeholders) {
        Objects.requireNonNull(target, "target");
        if (lines == null) return;
        for (String line : lines) {
            target.sendMessage(parse(applyExpander(line, target), placeholders));
        }
    }

    // ── joinLines() — helper kick/disconnect híváshoz ────────────────────────

    /**
     * Több sort egyetlen {@link Component}-té fűz {@code \n} elválasztóval.
     * Kick / disconnect esetén: {@code player.kick(TextUtil.joinLines(lines, ph))}.
     */
    public static Component joinLines(List<String> lines) {
        return joinLines(lines, null);
    }

    /** Több sort egyetlen {@link Component}-té fűz placeholderekkel. */
    public static Component joinLines(List<String> lines, Map<String, String> placeholders) {
        if (lines == null || lines.isEmpty()) return Component.empty();
        Component result = parse(lines.get(0), placeholders);
        for (int i = 1; i < lines.size(); i++) {
            result = result.append(Component.newline()).append(parse(lines.get(i), placeholders));
        }
        return result;
    }

    // ── sendActionBar() ──────────────────────────────────────────────────────

    /** Sorok action bar-ként küldve (sorok összefűzve). */
    public static void sendActionBar(Audience target, List<String> lines) {
        sendActionBar(target, lines, null);
    }

    /** Sorok action bar-ként küldve, placeholderekkel. */
    public static void sendActionBar(Audience target, List<String> lines, Map<String, String> placeholders) {
        Objects.requireNonNull(target, "target");
        if (lines == null || lines.isEmpty()) return;
        List<String> expanded = applyExpanderToAll(lines, target);
        target.sendActionBar(joinLines(expanded, placeholders));
    }

    // ── sendTitle() ──────────────────────────────────────────────────────────

    /**
     * Title + subtitle megjelenítése.
     * <ul>
     *   <li>{@code lines[0]} — title</li>
     *   <li>{@code lines[1]} — subtitle (opcionális)</li>
     *   <li>{@code lines[2]} — timing: {@code "fadein,stay,fadeout"} tickekben (opcionális)</li>
     * </ul>
     */
    public static void sendTitle(Audience target, List<String> lines) {
        sendTitle(target, lines, null);
    }

    /** Title + subtitle placeholderekkel — lásd {@link #sendTitle(Audience, List)}. */
    public static void sendTitle(Audience target, List<String> lines, Map<String, String> placeholders) {
        Objects.requireNonNull(target, "target");
        if (lines == null || lines.isEmpty()) return;
        Component title    = parse(applyExpander(lines.get(0), target), placeholders);
        Component subtitle = lines.size() > 1
                ? parse(applyExpander(lines.get(1), target), placeholders)
                : Component.empty();
        Title.Times times  = lines.size() > 2 ? parseTimes(lines.get(2)) : null;
        target.showTitle(Title.title(title, subtitle, times));
    }

    // ── sendTablist() ────────────────────────────────────────────────────────

    /** Tab-lista fejléc + lábléc beállítása. */
    public static void sendTablist(Audience target, List<String> header, List<String> footer) {
        sendTablist(target, header, footer, null);
    }

    /** Tab-lista fejléc + lábléc placeholderekkel. */
    public static void sendTablist(Audience target, List<String> header, List<String> footer,
                                   Map<String, String> placeholders) {
        Objects.requireNonNull(target, "target");
        Component h = (header != null && !header.isEmpty())
                ? joinLines(applyExpanderToAll(header, target), placeholders)
                : Component.empty();
        Component f = (footer != null && !footer.isEmpty())
                ? joinLines(applyExpanderToAll(footer, target), placeholders)
                : Component.empty();
        target.sendPlayerListHeaderAndFooter(h, f);
    }

    // ── internal helpers ─────────────────────────────────────────────────────

    private static String applyExpander(String line, Object context) {
        ExpanderConfig cfg = expanderConfig;
        PlaceholderExpander e = cfg.expander();
        if (e == null || line == null) return line;
        String expanded = e.expand(line, context);
        if (expanded == null) return line;
        return cfg.trusted() ? expanded : MINI.escapeTags(expanded);
    }

    private static List<String> applyExpanderToAll(List<String> lines, Object context) {
        ExpanderConfig cfg = expanderConfig;
        PlaceholderExpander e = cfg.expander();
        if (e == null) return lines;
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null) {
                result.add(null);
            } else {
                String expanded = e.expand(line, context);
                if (expanded == null) expanded = line;
                result.add(cfg.trusted() ? expanded : MINI.escapeTags(expanded));
            }
        }
        return result;
    }

    /**
     * Safely deserializes a MiniMessage string, falling back to plain text if parsing fails.
     * Malformed markup (e.g. {@code <gradient:#zzz>}) falls back to plain text instead of throwing.
     *
     * @param converted the MiniMessage string to deserialize
     * @param resolver  tag resolver for placeholders
     * @param original  original pre-conversion text used as last-resort fallback
     */
    private static Component safeDeserialize(String converted, TagResolver resolver, String original) {
        try {
            return MINI.deserialize(converted, resolver);
        } catch (Exception e) {
            org.slf4j.Logger log = debugLogger;
            if (log != null) {
                log.warn("MiniMessage parse failed, falling back to plain text. Input: '{}' Cause: {}",
                        truncate(converted),
                        e.getClass().getName() + ": " + truncate(e.getMessage()));
            }
            try {
                return Component.text(MINI.stripTags(converted));
            } catch (Exception e2) {
                if (log != null) {
                    log.warn("MiniMessage stripTags also failed, using raw original. Input: '{}' Cause: {}",
                            truncate(original),
                            e2.getClass().getName() + ": " + truncate(e2.getMessage()));
                }
                return Component.text(original == null ? "" : original);
            }
        }
    }

    /**
     * Truncates {@code s} to the first 200 chars for safe logging; {@code null} → {@code "null"}.
     * Avoids splitting a UTF-16 surrogate pair at the cut boundary, and strips ASCII control
     * characters (incl. newlines) so untrusted input cannot forge fake log lines.
     */
    private static String truncate(String s) {
        if (s == null) return "null";
        String result = s;
        if (s.length() > 200) {
            int end = 200;
            // If the last kept char is a high surrogate, its low surrogate would be cut off — back off one.
            if (Character.isHighSurrogate(s.charAt(end - 1))) {
                end--;
            }
            result = s.substring(0, end) + "…";
        }
        return result.replaceAll("[\\x00-\\x1F\\x7F]", " ");
    }

    /**
     * Parses title timing from {@code "fadein,stay,fadeout"} ticks format.
     * Returns {@code null} (= Minecraft default) if blank or invalid.
     */
    private static Title.Times parseTimes(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] parts = spec.split(",", 3);
        if (parts.length < 3) return null;
        try {
            int fadein  = Integer.parseInt(parts[0].trim());
            int stay    = Integer.parseInt(parts[1].trim());
            int fadeout = Integer.parseInt(parts[2].trim());
            if (fadein < 0 || stay < 0 || fadeout < 0) return null;
            return Title.Times.times(
                    Duration.ofMillis(fadein  * 50L),
                    Duration.ofMillis(stay    * 50L),
                    Duration.ofMillis(fadeout * 50L)
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Legacy ampersand/section-sign kódok és hex minták konverziója MiniMessage tag-formátumra.
     *
     * <p>Mindkét legacy prefix elfogadott: {@code &} (ampersand) és {@code §} (section sign,
     * §). A PlaceholderAPI alapértelmezetten section-sign kódokat produkál, ezért mindkettőt
     * kezeljük.
     *
     * <p>Színkódok ({@code &0}–{@code &f}, {@code &#RRGGBB}) elé {@code <reset>} kerül,
     * hogy megakadályozzuk a formázás (pl. bold) átvérzését — ez megfelel a Legacy
     * Minecraft viselkedésnek (egy színkód implicit resetelte a formázást).
     * Formázó kódok ({@code &k}–{@code &o}) nem resetelnek.
     */
    private static String convertLegacy(String s) {
        int len = s.length();
        StringBuilder out = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < len) {
                char n = s.charAt(i + 1);
                if (n == '#' && i + 8 <= len && isHex6(s, i + 2)) {
                    out.append("<reset><#");
                    for (int j = 0; j < 6; j++) {
                        out.append(Character.toLowerCase(s.charAt(i + 2 + j)));
                    }
                    out.append('>');
                    i += 8;
                    continue;
                }
                char lower = Character.toLowerCase(n);
                if (LEGACY_CODES.indexOf(lower) >= 0) {
                    out.append(legacyToTag(lower));
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isHex6(String s, int start) {
        if (start + 6 > s.length()) return false;
        for (int j = 0; j < 6; j++) {
            if (HEX_CHARS.indexOf(s.charAt(start + j)) < 0) return false;
        }
        return true;
    }

    private static String legacyToTag(char c) {
        return switch (c) {
            // Színkódok: <reset> + szín — bold/italic bleed megakadályozásához
            case '0' -> "<reset><black>";
            case '1' -> "<reset><dark_blue>";
            case '2' -> "<reset><dark_green>";
            case '3' -> "<reset><dark_aqua>";
            case '4' -> "<reset><dark_red>";
            case '5' -> "<reset><dark_purple>";
            case '6' -> "<reset><gold>";
            case '7' -> "<reset><gray>";
            case '8' -> "<reset><dark_gray>";
            case '9' -> "<reset><blue>";
            case 'a' -> "<reset><green>";
            case 'b' -> "<reset><aqua>";
            case 'c' -> "<reset><red>";
            case 'd' -> "<reset><light_purple>";
            case 'e' -> "<reset><yellow>";
            case 'f' -> "<reset><white>";
            // Formázók: NEM resetelnek
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default  -> "&" + c;
        };
    }
}
