package hu.patriksgit.paxapi.text;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TextUtilTest {

    @AfterEach
    void resetExpander() {
        TextUtil.setExpander(null);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    // ── parse() ──────────────────────────────────────────────────────────────

    @Test
    void parsesLegacyAmpersand() {
        Component c = TextUtil.parse("&aHello");
        assertEquals("Hello", plain(c));
        assertTrue(componentTreeContainsColor(c, NamedTextColor.GREEN));
    }

    @Test
    void parsesHexAmpersand() {
        Component c = TextUtil.parse("&#FF8800Hi");
        assertEquals("Hi", plain(c));
    }

    @Test
    void parsesHexAngleBracket() {
        Component c = TextUtil.parse("<#FF8800>X");
        assertEquals("X", plain(c));
    }

    @Test
    void parsesMiniMessageBold() {
        Component c = TextUtil.parse("<bold>X</bold>");
        assertEquals("X", plain(c));
        assertTrue(componentTreeContainsDecoration(c, TextDecoration.BOLD));
    }

    @Test
    void parsesMiniMessageGradient() {
        Component c = TextUtil.parse("<gradient:#FF0000:#00FF00>AB</gradient>");
        assertEquals("AB", plain(c));
    }

    @Test
    void parsesPlaceholder() {
        Component c = TextUtil.parse("Üdv %name%!", Map.of("name", "Steve"));
        assertEquals("Üdv Steve!", plain(c));
    }

    @Test
    void placeholderValueIsNotMiniMessageParsed() {
        Component c = TextUtil.parse("Üdv %name%!", Map.of("name", "<bold>Hacker</bold>"));
        assertEquals("Üdv <bold>Hacker</bold>!", plain(c));
    }

    @Test
    void nullInputReturnsEmptyComponent() {
        Component c = TextUtil.parse(null);
        assertEquals("", plain(c));
        assertEquals(Component.empty(), c);
    }

    @Test
    void nullPlaceholdersTreatedAsEmpty() {
        Component c = TextUtil.parse("&aHi", null);
        assertEquals("Hi", plain(c));
    }

    @Test
    void mixedLegacyAndMiniMessage() {
        Component c = TextUtil.parse("&aHi <bold>X</bold>");
        assertEquals("Hi X", plain(c));
    }

    @Test
    void invalidHexLeftLiteral() {
        Component c = TextUtil.parse("&#XYZ123");
        assertEquals("&#XYZ123", plain(c));
    }

    @Test
    void unknownPlaceholderLeftLiteral() {
        Component c = TextUtil.parse("Hi %unknown%!", Map.of("known", "x"));
        assertEquals("Hi %unknown%!", plain(c));
    }

    // ── parseLegacyOnly() ───────────────────────────────────────────────────────

    @Test
    void parseLegacyOnlyNullReturnsEmpty() {
        assertEquals(Component.empty(), TextUtil.parseLegacyOnly(null));
    }

    @Test
    void parseLegacyOnlyRendersLegacyColor() {
        Component c = TextUtil.parseLegacyOnly("&5Hello");
        assertEquals("Hello", plain(c));
        assertTrue(componentTreeContainsColor(c, NamedTextColor.DARK_PURPLE));
    }

    @Test
    void parseLegacyOnlyRendersHexColor() {
        Component c = TextUtil.parseLegacyOnly("&#FF8800Hi");
        assertEquals("Hi", plain(c));
    }

    @Test
    void parseLegacyOnlyEscapesRawMiniMessageTagsToLiteralText() {
        String input = "&5Hi <click:run_command:'/op me'>click me</click>";
        Component c = TextUtil.parseLegacyOnly(input);
        assertEquals("Hi <click:run_command:'/op me'>click me</click>", plain(c));
        assertFalse(componentTreeHasClickEvent(c));
    }

    @Test
    void parseLegacyOnlyEscapesHoverTag() {
        Component c = TextUtil.parseLegacyOnly("<hover:show_text:'gotcha'>hi</hover>");
        assertEquals("<hover:show_text:'gotcha'>hi</hover>", plain(c));
    }

    // ── bold-bleed fix ───────────────────────────────────────────────────────
    // Color codes (&0-&f, &#RRGGBB) now prepend <reset> to stop bold/italic from bleeding.

    @Test
    void boldStillAppliesWhenUsingFormattingCode() {
        // &l alone should still produce bold
        Component c = TextUtil.parse("&lBold");
        assertTrue(componentTreeContainsDecoration(c, TextDecoration.BOLD));
    }

    @Test
    void resetCodeStillWorks() {
        Component c = TextUtil.parse("&lBold&rNormal");
        assertEquals("BoldNormal", plain(c));
    }

    @Test
    void colorCodeAfterBoldProducesNoNodeWithBothBoldAndGray() {
        // &l&7text: the component tree must NOT contain a node that has BOTH gray color AND bold.
        // Old behavior (<bold><gray>text): one node has both → test would fail.
        // New behavior (<bold><reset><gray>text): no single node has both → test passes.
        Component c = TextUtil.parse("&l&7text");
        assertEquals("text", plain(c));
        assertFalse(anyNodeHasColorAndDecoration(c, NamedTextColor.GRAY, TextDecoration.BOLD),
                "No component node should simultaneously have gray color and bold decoration");
    }

    @Test
    void hexColorAfterBoldProducesNoNodeWithBothColors() {
        // &#FF0000&lSTAFF &7» — the » should be in gray without bold
        Component c = TextUtil.parse("&#FF0000&lSTAFF &7»");
        assertEquals("STAFF »", plain(c));
        assertFalse(anyNodeHasColorAndDecoration(c, NamedTextColor.GRAY, TextDecoration.BOLD),
                "Gray section should not have bold on the same component node");
    }

    // ── send() backward compat ───────────────────────────────────────────────

    @Test
    void sendDeliversParsedComponentToAudience() {
        CapturingAudience audience = new CapturingAudience();
        TextUtil.send(audience, "&aHi");
        assertEquals(1, audience.received.size());
        assertEquals("Hi", plain(audience.received.get(0)));
    }

    @Test
    void sendWithPlaceholdersDelivers() {
        CapturingAudience audience = new CapturingAudience();
        TextUtil.send(audience, "Üdv %name%!", Map.of("name", "Steve"));
        assertEquals(1, audience.received.size());
        assertEquals("Üdv Steve!", plain(audience.received.get(0)));
    }

    @Test
    void sendNullAudienceThrows() {
        assertThrows(NullPointerException.class,
                () -> TextUtil.send(null, "Hi"));
    }

    // ── sendChat() ───────────────────────────────────────────────────────────

    @Test
    void sendChatListSendsEachLineAsMessage() {
        CapturingAudience audience = new CapturingAudience();
        TextUtil.sendChat(audience, List.of("&aLine1", "&bLine2"));
        assertEquals(2, audience.received.size());
        assertEquals("Line1", plain(audience.received.get(0)));
        assertEquals("Line2", plain(audience.received.get(1)));
    }

    @Test
    void sendChatWithPlaceholders() {
        CapturingAudience audience = new CapturingAudience();
        TextUtil.sendChat(audience, List.of("Hi %name%!"), Map.of("name", "Bela"));
        assertEquals(1, audience.received.size());
        assertEquals("Hi Bela!", plain(audience.received.get(0)));
    }

    @Test
    void sendChatCallsExpander() {
        TextUtil.setExpander((text, player) -> text.replace("[EXT]", "expanded"));
        CapturingAudience audience = new CapturingAudience();
        TextUtil.sendChat(audience, List.of("[EXT]!"));
        assertEquals("expanded!", plain(audience.received.get(0)));
    }

    @Test
    void sendChatNullLinesDoesNothing() {
        CapturingAudience audience = new CapturingAudience();
        TextUtil.sendChat(audience, (List<String>) null);
        assertEquals(0, audience.received.size());
    }

    // ── joinLines() ──────────────────────────────────────────────────────────

    @Test
    void joinLinesSingleLine() {
        Component c = TextUtil.joinLines(List.of("&aHello"));
        assertEquals("Hello", plain(c));
    }

    @Test
    void joinLinesMultipleLines() {
        Component c = TextUtil.joinLines(List.of("Line1", "Line2", "Line3"));
        assertEquals("Line1\nLine2\nLine3", plain(c));
    }

    @Test
    void joinLinesNullOrEmptyReturnsEmpty() {
        assertEquals(Component.empty(), TextUtil.joinLines(null));
        assertEquals(Component.empty(), TextUtil.joinLines(List.of()));
    }

    @Test
    void joinLinesWithPlaceholders() {
        Component c = TextUtil.joinLines(List.of("Hi %name%", "Bye %name%"), Map.of("name", "Tomi"));
        assertEquals("Hi Tomi\nBye Tomi", plain(c));
    }

    // ── PlaceholderExpander ──────────────────────────────────────────────────

    @Test
    void expanderIsAppliedBeforeInternalPlaceholders() {
        // Expander replaces [EXT] with the value of an internal placeholder (%name%)
        // Then the internal placeholder resolution fills %name% = "Tomi"
        // Result should be "Tomi"
        TextUtil.setExpander((text, player) -> text.replace("[EXT]", "%name%"));
        CapturingAudience a = new CapturingAudience();
        TextUtil.sendChat(a, List.of("[EXT]"), Map.of("name", "Tomi"));
        assertEquals("Tomi", plain(a.received.get(0)));
    }

    @Test
    void expanderReceivesAudienceAsContext() {
        CapturingAudience audience = new CapturingAudience();
        List<Object> capturedContexts = new ArrayList<>();
        TextUtil.setExpander((text, player) -> {
            capturedContexts.add(player);
            return text;
        });
        TextUtil.sendChat(audience, List.of("hi"));
        assertEquals(1, capturedContexts.size());
        assertSame(audience, capturedContexts.get(0));
    }

    // ── strip() ──────────────────────────────────────────────────────────────

    @Test
    void stripNullReturnsEmpty() {
        assertEquals("", TextUtil.strip(null));
    }

    @Test
    void stripEmptyReturnsEmpty() {
        assertEquals("", TextUtil.strip(""));
    }

    @Test
    void stripLegacyCodes() {
        assertEquals("Hello", TextUtil.strip("&aHello"));
    }

    @Test
    void stripHexCodes() {
        assertEquals("Hi", TextUtil.strip("&#FF8800Hi"));
    }

    @Test
    void stripMiniMessageTags() {
        assertEquals("Bold", TextUtil.strip("<bold>Bold</bold>"));
    }

    @Test
    void stripMixedFormats() {
        assertEquals("Hello World", TextUtil.strip("&a<bold>Hello</bold> &#FF0000World"));
    }

    // ── builder() ────────────────────────────────────────────────────────────

    @Test
    void builderBasicPlaceholder() {
        Component c = TextUtil.builder("Üdv %name%!")
                .placeholder("name", "Steve")
                .build();
        assertEquals("Üdv Steve!", plain(c));
    }

    @Test
    void builderPlaceholderValueIsInjectionSafe() {
        Component c = TextUtil.builder("Üdv %name%!")
                .placeholder("name", "<bold>Hacker</bold>")
                .build();
        assertEquals("Üdv <bold>Hacker</bold>!", plain(c));
    }

    @Test
    void builderLastClickWins() {
        // suggest() then run() — run() should win
        Component c = TextUtil.builder("click me")
                .suggest("/suggest")
                .run("/run")
                .build();
        assertNotNull(c.clickEvent());
        assertEquals(net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND,
                c.clickEvent().action());
        assertEquals("/run", c.clickEvent().value());
    }

    @Test
    void builderHoverAsString() {
        Component c = TextUtil.builder("hover me")
                .hover("&aTooltip")
                .build();
        assertNotNull(c.hoverEvent());
    }

    @Test
    void builderNoClickNoHoverByDefault() {
        Component c = TextUtil.builder("plain").build();
        assertNull(c.clickEvent());
        assertNull(c.hoverEvent());
    }

    @Test
    void builderNullTextReturnsEmptyComponent() {
        Component c = TextUtil.builder(null).build();
        assertEquals(Component.empty(), c);
    }

    @Test
    void builderNullPlaceholderValueTreatedAsEmpty() {
        Component c = TextUtil.builder("Hi %name%!")
                .placeholder("name", null)
                .build();
        assertEquals("Hi !", plain(c));
    }

    // ── formatChat() ─────────────────────────────────────────────────────────

    @Test
    void formatChatSubstitutesComponentPlaceholder() {
        Component name = Component.text("Steve");
        Component result = TextUtil.formatChat("&7Hello %name%!", Map.of("name", name));
        assertEquals("Hello Steve!", plain(result));
    }

    @Test
    void formatChatMissingPlaceholderLeftLiteral() {
        Component result = TextUtil.formatChat("Hi %unknown%!", Map.of("name", Component.text("x")));
        assertEquals("Hi %unknown%!", plain(result));
    }

    @Test
    void formatChatNullFormatReturnsEmpty() {
        Component result = TextUtil.formatChat(null, Map.of());
        assertEquals(Component.empty(), result);
    }

    @Test
    void formatChatNullMapParsesFormatOnly() {
        Component result = TextUtil.formatChat("&aHello", null);
        assertEquals("Hello", plain(result));
    }

    @Test
    void formatChatComponentValuePreserved() {
        // Component with bold decoration should survive the substitution
        Component bold = Component.text("BOLD").decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);
        Component result = TextUtil.formatChat("%msg%", Map.of("msg", bold));
        assertTrue(componentTreeContainsDecoration(result, net.kyori.adventure.text.format.TextDecoration.BOLD));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static boolean componentTreeContainsDecoration(Component c, TextDecoration deco) {
        if (c.decoration(deco) == TextDecoration.State.TRUE) return true;
        for (Component child : c.children()) {
            if (componentTreeContainsDecoration(child, deco)) return true;
        }
        return false;
    }

    private static boolean componentTreeContainsColor(Component c, NamedTextColor color) {
        if (color.equals(c.color())) return true;
        for (Component child : c.children()) {
            if (componentTreeContainsColor(child, color)) return true;
        }
        return false;
    }

    private static boolean componentTreeHasClickEvent(Component c) {
        if (c.clickEvent() != null) return true;
        for (Component child : c.children()) {
            if (componentTreeHasClickEvent(child)) return true;
        }
        return false;
    }

    /** Returns true if any single component node in the tree has BOTH the given color and decoration. */
    private static boolean anyNodeHasColorAndDecoration(Component c, NamedTextColor color, TextDecoration deco) {
        boolean hasColor = color.equals(c.color());
        boolean hasDeco  = c.decoration(deco) == TextDecoration.State.TRUE;
        if (hasColor && hasDeco) return true;
        for (Component child : c.children()) {
            if (anyNodeHasColorAndDecoration(child, color, deco)) return true;
        }
        return false;
    }

    private static final class CapturingAudience implements Audience {
        final List<Component> received = new ArrayList<>();

        @Override
        public void sendMessage(Component message) {
            received.add(message);
        }
    }

    // ── FIX A: malformed MiniMessage must not throw ───────────────────────────

    @Test
    void malformedMiniMessageDoesNotThrow() {
        // <gradient:#zzz> is malformed — zzz is not a valid hex color
        assertDoesNotThrow(() -> TextUtil.parse("<gradient:#zzz>broken"));
    }

    @Test
    void malformedMiniMessageReturnsNonNullComponent() {
        Component c = TextUtil.parse("<gradient:#zzz>broken");
        assertNotNull(c);
    }

    @Test
    void malformedMiniMessagePlainTextContainsContent() {
        Component c = TextUtil.parse("<gradient:#zzz>broken");
        assertTrue(plain(c).contains("broken"),
                "Plain text fallback must contain the literal content; got: " + plain(c));
    }

    @Test
    void malformedMiniMessageInFormatChatDoesNotThrow() {
        assertDoesNotThrow(() -> TextUtil.formatChat("<gradient:#zzz>chat", Map.of()));
    }

    // ── FIX C: reserved-tag placeholder keys must not shadow built-ins ────────

    @Test
    void reservedKeyResetNotInjected() {
        // %reset% is a reserved MiniMessage tag — placeholder must be skipped
        Component c = TextUtil.parse("&lBold %reset% text", Map.of("reset", "INJECTED"));
        String text = plain(c);
        assertTrue(text.contains("%reset%"),
                "Reserved placeholder key must be left literal; got: " + text);
        assertFalse(text.contains("INJECTED"),
                "Reserved placeholder value must not appear in output; got: " + text);
    }

    @Test
    void reservedKeyBoldNotInjected() {
        Component c = TextUtil.parse("Hello %bold% world", Map.of("bold", "INJECTED"));
        String text = plain(c);
        assertTrue(text.contains("%bold%"), "Reserved key 'bold' must be left literal; got: " + text);
    }

    @Test
    void nonReservedKeyStillInjected() {
        // A normal key like "player" must still be replaced
        Component c = TextUtil.parse("Hello %player%!", Map.of("player", "Steve"));
        assertEquals("Hello Steve!", plain(c));
    }

    // ── FIX E: section-sign (§) legacy codes treated same as & ───────────────

    @Test
    void sectionSignColorCodeStripped() {
        // §a is the green color code — stripping should give "Hello" without §a
        String stripped = TextUtil.strip("§aHello");
        assertEquals("Hello", stripped);
    }

    @Test
    void sectionSignAndAmpersandStrippedIdentically() {
        // §a and &a should produce the same stripped output
        assertEquals(TextUtil.strip("&aHello"), TextUtil.strip("§aHello"));
    }

    @Test
    void sectionSignParsedToGreenComponent() {
        Component c = TextUtil.parse("§aHello");
        assertEquals("Hello", plain(c));
        assertTrue(componentTreeContainsColor(c, NamedTextColor.GREEN),
                "§a must produce green color component");
    }

    @Test
    void sectionSignHexCode() {
        // §#FF0000Hi — hex via section sign
        Component c = TextUtil.parse("§#FF0000Hi");
        assertEquals("Hi", plain(c));
    }

    // ── FIX D: expander output escaped by default (injection-safe) ────────────

    @Test
    void expanderDefaultEscapesMiniMessageTags() {
        // Expander returns a string containing MiniMessage tags — they must be escaped
        TextUtil.setExpander((text, ctx) -> "<red>x</red>" + text);
        CapturingAudience a = new CapturingAudience();
        TextUtil.sendChat(a, "suffix");
        assertFalse(a.received.isEmpty());
        String text = plain(a.received.get(0));
        // "<red>" must appear literally in the output, NOT be colored red
        assertTrue(text.contains("<red>"),
                "Default-escaped expander output must contain literal <red>; got: " + text);
    }

    @Test
    void expanderTrustedDoesNotEscapeMiniMessageTags() {
        // With trust=true, <red>...</red> must render as colored text (plain text "x")
        TextUtil.setExpander((text, ctx) -> "<red>x</red>", true);
        CapturingAudience a = new CapturingAudience();
        TextUtil.sendChat(a, "");
        assertFalse(a.received.isEmpty());
        String text = plain(a.received.get(0));
        // The <red> tags are parsed, so "x" appears without angle brackets
        assertFalse(text.contains("<red>"),
                "Trusted expander output must have <red> parsed (not literal); got: " + text);
        assertTrue(text.contains("x"), "Trusted expander output must contain the 'x' text; got: " + text);
    }

    @Test
    void setExpanderSingleArgResetsTrustToFalse() {
        // setExpander(e, true) then setExpander(e2) should reset trust to false
        TextUtil.setExpander((text, ctx) -> text, true);
        TextUtil.setExpander((text, ctx) -> "<red>x</red>" + text);
        CapturingAudience a = new CapturingAudience();
        TextUtil.sendChat(a, "suffix");
        String text = plain(a.received.get(0));
        assertTrue(text.contains("<red>"),
                "After setExpander(e) (no trust param), trust must be reset to false; got: " + text);
    }

    // ── round-5: placeholder dedup / case-collision (first-wins) ──────────────

    @Test
    void caseCollidingPlaceholderKeysFirstInFormatWins() {
        // Keys "Name" and "name" both lowercase to tag "name". The first occurrence in
        // the FORMAT string ("%Name%") registers tag name=Alice; the second ("%name%")
        // collides on the dedup set and reuses the already-registered resolver.
        // Pins current first-wins-by-format-order behaviour: both render "Alice".
        Component c = TextUtil.parse("%Name% %name%", Map.of("Name", "Alice", "name", "Bob"));
        assertEquals("Alice Alice", plain(c));
    }

    @Test
    void repeatedSamePlaceholderTokenUsesSingleResolver() {
        // "%x% %x%" — both tokens must be replaced by the one resolver for x.
        Component c = TextUtil.parse("%x% %x%", Map.of("x", "val"));
        assertEquals("val val", plain(c));
    }

    @Test
    void formatChatCaseCollidingKeysFirstInFormatWins() {
        // Same first-wins contract for formatChat's Component-valued placeholders.
        Component c = TextUtil.formatChat("%Name% %name%",
                Map.of("Name", Component.text("Alice"), "name", Component.text("Bob")));
        assertEquals("Alice Alice", plain(c));
    }

    @Test
    void formatChatRepeatedSameTokenUsesSingleResolver() {
        Component c = TextUtil.formatChat("%x% %x%", Map.of("x", Component.text("val")));
        assertEquals("val val", plain(c));
    }

    // ── round-5: debug logger + safeDeserialize WARN sanitization ─────────────

    @AfterEach
    void resetDebugLogger() {
        TextUtil.setDebugLogger(null);
    }

    @Test
    void malformedMiniMessageFallsBackGracefully() {
        // This MiniMessage version is fully lenient, so odd/malformed input does not throw —
        // safeDeserialize's catch/log path is unreachable here. Instead of asserting on a WARN
        // (which is never emitted), pin the real fallback CONTRACT: parse()/strip() must never
        // throw and must return a non-null Component for malformed input, across versions.
        String[] malformed = {
                "<gradient:red>x</gradient>",
                "<bold>oops",
                "<#zz>x"
        };
        for (String input : malformed) {
            Component c = assertDoesNotThrow(() -> TextUtil.parse(input),
                    "parse must not throw on malformed input: " + input);
            assertNotNull(c, "parse must return a non-null Component for: " + input);
            assertDoesNotThrow(() -> TextUtil.strip(input),
                    "strip must not throw on malformed input: " + input);
        }
    }

    @Test
    void debugLoggerNotInvokedWhenUnset() {
        // With no debug logger set, a malformed parse must not throw and must still fall back.
        TextUtil.setDebugLogger(null);
        assertDoesNotThrow(() -> TextUtil.parse("<gradient:#zzz>x"));
    }

    // ── round-5: truncate() surrogate-boundary + sanitization (reflection) ────

    private static String truncate(String s) throws Exception {
        java.lang.reflect.Method m = TextUtil.class.getDeclaredMethod("truncate", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    @Test
    void truncateNullReturnsLiteralNull() throws Exception {
        assertEquals("null", truncate(null));
    }

    @Test
    void truncateShortStringUnchanged() throws Exception {
        assertEquals("hello", truncate("hello"));
    }

    @Test
    void truncateExactly200Unchanged() throws Exception {
        String s = "a".repeat(200);
        assertEquals(s, truncate(s));
    }

    @Test
    void truncateOver200IsCappedWithEllipsis() throws Exception {
        String s = "a".repeat(201);
        String out = truncate(s);
        // 200 kept chars + the single ellipsis char "…"
        assertEquals("a".repeat(200) + "…", out);
        assertEquals(201, out.length());
    }

    @Test
    void truncateDoesNotLeaveOrphanedHighSurrogate() throws Exception {
        // 199 'a' + a surrogate pair (😀 = 😀): char 199 is the high surrogate, char
        // 200 the low surrogate. Cutting at 200 would keep the high surrogate and drop its low
        // half → orphan. truncate must back off one, keeping 199 'a' + "…" (length 200).
        String s = "a".repeat(199) + "😀";
        assertEquals(201, s.length());
        String out = truncate(s);
        assertEquals("a".repeat(199) + "…", out);
        assertEquals(200, out.length());
        // No orphaned (unpaired) high surrogate anywhere in the output.
        for (int i = 0; i < out.length(); i++) {
            assertFalse(Character.isHighSurrogate(out.charAt(i)),
                    "Output must not contain an orphaned high surrogate");
        }
    }

    @Test
    void truncateSanitizesControlCharsToSpaces() throws Exception {
        // Newline, tab, carriage return, NUL and DEL must all become spaces.
        assertEquals("a b c d", truncate("a\nb\tc\rd"));
        assertEquals("x y", truncate("x\u007fy"));
        assertEquals("a b", truncate("a\u0000b"));
    }
}
