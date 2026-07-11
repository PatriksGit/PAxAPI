package hu.patriksgit.paxapi.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fluent builder for interactive (hoverable / clickable) {@link Component}s.
 *
 * <p>Create via {@link TextUtil#builder(String)}:
 * <pre>{@code
 * Component msg = TextUtil.builder("&7%sender% &8→ &7%receiver%")
 *     .placeholder("sender", senderName)
 *     .placeholder("receiver", targetName)
 *     .hover("&7Kattints a válaszhoz!")
 *     .suggest("/msg " + senderName + " ")
 *     .build();
 * }</pre>
 *
 * <p>Placeholder values are injection-safe — MiniMessage tags inside values render
 * as plain text (same contract as {@link TextUtil#parse(String, Map)}).
 *
 * <p>If multiple click methods ({@link #suggest}, {@link #run}, {@link #url},
 * {@link #copy}) are called, the last call wins.
 */
public final class ComponentBuilder {

    private final String text;
    private final Map<String, String> placeholders = new LinkedHashMap<>();
    private Component hoverComponent = null;
    private ClickEvent clickEvent = null;

    ComponentBuilder(String text) {
        this.text = text != null ? text : "";
    }

    /** Adds a placeholder — value is injection-safe (MiniMessage tags are escaped). */
    public ComponentBuilder placeholder(String key, String value) {
        placeholders.put(key, value != null ? value : "");
        return this;
    }

    /** Adds multiple placeholders at once. */
    public ComponentBuilder placeholders(Map<String, String> ph) {
        if (ph != null) placeholders.putAll(ph);
        return this;
    }

    /**
     * Hover text — parsed with legacy + hex + MiniMessage.
     *
     * <p><b>Security:</b> {@code hoverText} is trusted developer input, parsed as live
     * MiniMessage. Unlike {@link #suggest} / {@link #run}, untrusted text here carries no
     * command-execution risk (tooltips aren't clickable) — the risk is visual/formatting
     * spoofing, e.g. a player name containing {@code <red><bold>FAKE STAFF WARNING} reformatting
     * the tooltip. Interpolate untrusted values as {@link #placeholder placeholders} instead.
     */
    public ComponentBuilder hover(String hoverText) {
        this.hoverComponent = hoverText != null ? TextUtil.parse(hoverText) : null;
        return this;
    }

    /** Hover text as a pre-built Component. */
    public ComponentBuilder hover(Component component) {
        this.hoverComponent = component;
        return this;
    }

    /**
     * Click: suggest a command (fills the chat bar). Last click call wins.
     *
     * <p><b>Security:</b> {@code command} is trusted developer input. Never build it from
     * unescaped player-controlled text — a viewer clicking it would run/pre-fill the crafted
     * command. Interpolate untrusted values as {@link #placeholder placeholders} (which are
     * injection-safe) inside the display text, not into this command string.
     */
    public ComponentBuilder suggest(String command) {
        this.clickEvent = ClickEvent.suggestCommand(command != null ? command : "");
        return this;
    }

    /**
     * Click: run a command immediately. Last click call wins.
     *
     * <p><b>Security:</b> {@code command} is trusted developer input and executes with the
     * <em>clicking viewer's</em> permissions. Never build it from player-controlled text — a
     * staff member clicking a crafted message would run the attacker's command.
     */
    public ComponentBuilder run(String command) {
        this.clickEvent = ClickEvent.runCommand(command != null ? command : "");
        return this;
    }

    /** Click: open a URL in the browser. Last click call wins. */
    public ComponentBuilder url(String url) {
        this.clickEvent = ClickEvent.openUrl(url != null ? url : "");
        return this;
    }

    /** Click: copy text to the clipboard. Last click call wins. */
    public ComponentBuilder copy(String text) {
        this.clickEvent = ClickEvent.copyToClipboard(text != null ? text : "");
        return this;
    }

    /** Builds the final Component with hover and click applied. */
    public Component build() {
        Component base = TextUtil.parse(text, placeholders.isEmpty() ? null : placeholders);
        if (hoverComponent != null) {
            base = base.hoverEvent(HoverEvent.showText(hoverComponent));
        }
        if (clickEvent != null) {
            base = base.clickEvent(clickEvent);
        }
        return base;
    }
}
