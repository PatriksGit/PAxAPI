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

    /** Hover text — parsed with legacy + hex + MiniMessage. */
    public ComponentBuilder hover(String hoverText) {
        this.hoverComponent = hoverText != null ? TextUtil.parse(hoverText) : null;
        return this;
    }

    /** Hover text as a pre-built Component. */
    public ComponentBuilder hover(Component component) {
        this.hoverComponent = component;
        return this;
    }

    /** Click: suggest a command (fills the chat bar). Last click call wins. */
    public ComponentBuilder suggest(String command) {
        this.clickEvent = ClickEvent.suggestCommand(command != null ? command : "");
        return this;
    }

    /** Click: run a command immediately. Last click call wins. */
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
