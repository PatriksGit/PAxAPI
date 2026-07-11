package hu.patriksgit.paxapi.text;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessagesFileTest {

    private static final Logger LOG = LoggerFactory.getLogger(MessagesFileTest.class);

    @TempDir
    Path tempDir;

    @AfterEach
    void resetExpander() {
        TextUtil.setExpander(null);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Recursively checks whether {@code c} or any child carries {@code color} — root-only would miss a nested node. */
    private static boolean componentTreeContainsColor(Component c, NamedTextColor color) {
        if (c.color() == color) return true;
        for (Component child : c.children()) {
            if (componentTreeContainsColor(child, color)) return true;
        }
        return false;
    }

    private Path yaml(String content) throws IOException {
        Path f = tempDir.resolve("messages.yml");
        Files.writeString(f, content);
        return f;
    }

    private static MessagesFile load(Path f) throws IOException {
        return MessagesFile.load(f, null, LOG);
    }

    private static final class Cap implements Audience {
        final List<Component> received = new ArrayList<>();
        @Override public void sendMessage(Component msg) { received.add(msg); }
    }

    // ── get() ─────────────────────────────────────────────────────────────────

    @Test
    void simpleStringKey() throws IOException {
        assertEquals("Hi!", plain(load(yaml("hello: \"&aHi!\"")).get("hello")));
    }

    @Test
    void nestedDotKey() throws IOException {
        assertEquals("OK", plain(load(yaml("login:\n  success: \"&aOK\"")).get("login.success")));
    }

    @Test
    void prefixSubstitution() throws IOException {
        MessagesFile msg = load(yaml("prefix: \"[P] \"\nwelcome: \"%prefix%Hi\""));
        assertEquals("[P] Hi", plain(msg.get("welcome")));
    }

    @Test
    void externalPrefixSupplierOverridesYaml() throws IOException {
        MessagesFile msg = MessagesFile.load(yaml("prefix: \"ignored\"\nwelcome: \"%prefix%Hi\""), null, LOG, () -> "[EXT] ");
        assertEquals("[EXT] Hi", plain(msg.get("welcome")));
    }

    @Test
    void missingKeyReturnsEmptyComponent() throws IOException {
        assertEquals(Component.empty(), load(yaml("a: b")).get("missing"));
    }

    @Test
    void getWithPlaceholders() throws IOException {
        assertEquals("Hi Steve!", plain(load(yaml("greet: \"Hi %name%!\"")).get("greet", Map.of("name", "Steve"))));
    }

    @Test
    void placeholderValueIsInjectionSafe() throws IOException {
        MessagesFile msg = load(yaml("greet: \"Hi %name%!\""));
        assertEquals("Hi <bold>H</bold>!", plain(msg.get("greet", Map.of("name", "<bold>H</bold>"))));
    }

    // ── list keys ─────────────────────────────────────────────────────────────

    @Test
    void listKeyGetJoinsLinesWithNewline() throws IOException {
        MessagesFile msg = load(yaml("lines:\n  - \"A\"\n  - \"B\""));
        assertEquals("A\nB", plain(msg.get("lines")));
    }

    @Test
    void getListReturnsEachLineAsSeparateComponent() throws IOException {
        MessagesFile msg = load(yaml("lines:\n  - \"&aA\"\n  - \"&bB\""));
        List<Component> list = msg.getList("lines");
        assertEquals(2, list.size());
        assertEquals("A", plain(list.get(0)));
        assertEquals("B", plain(list.get(1)));
    }

    @Test
    void getListWithPrefixSubstitution() throws IOException {
        MessagesFile msg = load(yaml("prefix: \"[P]\"\nlines:\n  - \"%prefix% A\"\n  - \"%prefix% B\""));
        List<Component> list = msg.getList("lines");
        assertEquals("[P] A", plain(list.get(0)));
        assertEquals("[P] B", plain(list.get(1)));
    }

    @Test
    void getListMissingKeyReturnsEmptyList() throws IOException {
        assertTrue(load(yaml("a: b")).getList("missing").isEmpty());
    }

    // ── send() ────────────────────────────────────────────────────────────────

    @Test
    void sendStringSendsOneMessage() throws IOException {
        Cap a = new Cap();
        load(yaml("msg: \"Hello\"")).send(a, "msg");
        assertEquals(1, a.received.size());
        assertEquals("Hello", plain(a.received.get(0)));
    }

    @Test
    void sendListSendsEachLineAsSeparateMessage() throws IOException {
        Cap a = new Cap();
        load(yaml("lines:\n  - \"A\"\n  - \"B\"")).send(a, "lines");
        assertEquals(2, a.received.size());
        assertEquals("A", plain(a.received.get(0)));
        assertEquals("B", plain(a.received.get(1)));
    }

    @Test
    void sendWithPlaceholders() throws IOException {
        Cap a = new Cap();
        load(yaml("greet: \"Hi %name%!\"")).send(a, "greet", Map.of("name", "Tomi"));
        assertEquals("Hi Tomi!", plain(a.received.get(0)));
    }

    @Test
    void sendMissingKeyDoesNotSendEmptyMessage() throws IOException {
        Cap a = new Cap();
        load(yaml("a: b")).send(a, "missing");
        assertEquals(0, a.received.size());
    }

    @Test
    void sendAppliesPlaceholderExpander() throws IOException {
        TextUtil.setExpander((text, ctx) -> text.replace("[EXT]", "expanded"));
        Cap a = new Cap();
        load(yaml("msg: \"[EXT]!\"")).send(a, "msg");
        assertEquals("expanded!", plain(a.received.get(0)));
    }

    // ── broadcast() ───────────────────────────────────────────────────────────

    @Test
    void broadcastSendsToAllTargets() throws IOException {
        Cap a1 = new Cap(), a2 = new Cap(), a3 = new Cap();
        load(yaml("msg: \"Hi\"")).broadcast(List.of(a1, a2, a3), "msg");
        assertEquals(1, a1.received.size());
        assertEquals(1, a2.received.size());
        assertEquals(1, a3.received.size());
    }

    @Test
    void broadcastNullTargetsIsNoop() throws IOException {
        assertDoesNotThrow(() -> load(yaml("msg: \"Hi\"")).broadcast(null, "msg"));
    }

    @Test
    void broadcastWithPlaceholders() throws IOException {
        Cap a1 = new Cap(), a2 = new Cap();
        load(yaml("msg: \"Hi %name%!\"")).broadcast(List.of(a1, a2), "msg", Map.of("name", "all"));
        assertEquals("Hi all!", plain(a1.received.get(0)));
        assertEquals("Hi all!", plain(a2.received.get(0)));
    }

    // ── formatChat() ──────────────────────────────────────────────────────────

    @Test
    void formatChatResolvesKeyAndPrefix() throws IOException {
        MessagesFile msg = load(yaml("prefix: \"[P] \"\nchat.format: \"%prefix%%name% » %text%\""));
        Component result = msg.formatChat("chat.format", Map.of(
                "name", Component.text("Steve"),
                "text", Component.text("Hello")
        ));
        assertEquals("[P] Steve » Hello", plain(result));
    }

    @Test
    void formatChatMissingKeyReturnsEmpty() throws IOException {
        assertEquals(Component.empty(), load(yaml("a: b")).formatChat("missing", Map.of()));
    }

    // ── reload() ──────────────────────────────────────────────────────────────

    @Test
    void reloadPicksUpChangedContent() throws IOException {
        Path f = yaml("msg: \"v1\"");
        MessagesFile msg = load(f);
        assertEquals("v1", plain(msg.get("msg")));
        Files.writeString(f, "msg: \"v2\"");
        msg.reload();
        assertEquals("v2", plain(msg.get("msg")));
    }

    @Test
    void reloadClearsWarnCache() throws IOException {
        Path f = yaml("a: b");
        MessagesFile msg = load(f);
        msg.get("missing");
        msg.reload();
        Files.writeString(f, "missing: \"now here\"");
        msg.reload();
        assertEquals("now here", plain(msg.get("missing")));
    }

    // ── round-5: missing-key WARN warn-once + reload re-arms it ────────────────

    private static long missingWarns(CapturingLogger log) {
        return log.warns().stream()
                .filter(e -> e.pattern != null && e.pattern.startsWith("Missing messages.yml key"))
                .count();
    }

    @Test
    void missingKeyWarnsOnceThenSilentUntilReload() throws IOException {
        CapturingLogger log = new CapturingLogger();
        // "a: b" produces no incidental load/reload warns, so the count is clean.
        Path f = yaml("a: b");
        MessagesFile msg = MessagesFile.load(f, null, log);

        // First lookup of a missing key → exactly one WARN.
        msg.get("missing");
        assertEquals(1, missingWarns(log), "First missing-key get must WARN once");

        // Second lookup before reload → still one (warn-once cache suppresses it).
        msg.get("missing");
        assertEquals(1, missingWarns(log), "Repeated missing-key get must NOT warn again before reload");

        // After reload (file unchanged → key still missing), the warn cache is cleared,
        // so the next lookup WARNs again.
        msg.reload();
        msg.get("missing");
        assertEquals(2, missingWarns(log), "Missing-key WARN must fire again after reload()");
    }

    // ── auto-extract ──────────────────────────────────────────────────────────

    @Test
    void autoExtractsFromDefaultResourceIfFileMissing() throws IOException {
        Path f = tempDir.resolve("new.yml");
        byte[] content = "extracted: \"yes\"".getBytes();
        MessagesFile msg = MessagesFile.load(f, new ByteArrayInputStream(content), LOG);
        assertTrue(Files.exists(f));
        assertEquals("yes", plain(msg.get("extracted")));
    }

    @Test
    void throwsIfFileMissingAndNoDefault() {
        Path f = tempDir.resolve("nonexistent.yml");
        assertThrows(IOException.class, () -> MessagesFile.load(f, null, LOG));
    }

    // ── rawPrefix() ───────────────────────────────────────────────────────────

    @Test
    void rawPrefixFromYaml() throws IOException {
        assertEquals("[Server] ", load(yaml("prefix: \"[Server] \"")).rawPrefix());
    }

    @Test
    void rawPrefixFromSupplier() throws IOException {
        MessagesFile msg = MessagesFile.load(yaml("prefix: \"ignored\""), null, LOG, () -> "[Supplied] ");
        assertEquals("[Supplied] ", msg.rawPrefix());
    }

    @Test
    void rawPrefixCanBeChainedAsSupplier() throws IOException {
        MessagesFile parent = load(yaml("prefix: \"[Parent] \""));
        MessagesFile child  = MessagesFile.load(yaml("greet: \"%prefix%Hi\""), null, LOG, parent::rawPrefix);
        assertEquals("[Parent] Hi", plain(child.get("greet")));
    }

    // ── FIX B: null prefix supplier must not cause NPE ────────────────────────

    @Test
    void nullPrefixSupplierDoesNotThrow() throws IOException {
        // prefixSupplier.get() returns null — get() must not throw NPE
        MessagesFile msg = MessagesFile.load(yaml("greet: \"%prefix%Hi\""), null, LOG, () -> null);
        assertDoesNotThrow(() -> msg.get("greet"));
    }

    @Test
    void nullPrefixSupplierReturnsComponentWithoutPrefix() throws IOException {
        MessagesFile msg = MessagesFile.load(yaml("greet: \"%prefix%Hi\""), null, LOG, () -> null);
        // With null prefix the %prefix% token should be replaced by "" (empty)
        String text = plain(msg.get("greet"));
        assertFalse(text.contains("%prefix%"),
                "Null prefix must not leave literal %prefix% token; got: " + text);
        assertTrue(text.contains("Hi"), "Content after %prefix% must still appear; got: " + text);
    }

    @Test
    void nullPrefixSupplierInGetList() throws IOException {
        MessagesFile msg = MessagesFile.load(yaml("lines:\n  - \"%prefix%A\"\n  - \"%prefix%B\""), null, LOG, () -> null);
        assertDoesNotThrow(() -> msg.getList("lines"));
        List<Component> list = msg.getList("lines");
        assertEquals(2, list.size());
        assertEquals("A", plain(list.get(0)));
    }

    // ── multi-prefix (named prefixes, YAML map under prefix:) ──────────────────
    //
    // When `prefix:` in the YAML is a map (e.g. `prefix: {helpop: "...", staffchat: "..."}`),
    // each entry becomes a `%<name>-prefix%` token usable in ANY message, auto-substituted and
    // correctly colored — no placeholder needs to be passed by the caller. This is distinct from
    // the legacy flat `%prefix%` single-string form, which keeps working unchanged (see
    // prefixSubstitution() above) and is unaffected by this feature.

    @Test
    void namedPrefixAutoSubstitutesInGetWithoutCallerSupplyingIt() throws IOException {
        MessagesFile msg = load(yaml("""
                prefix:
                  helpop: "[HelpOp] "
                helpop:
                  sent: "%helpop-prefix%Hi"
                """));
        assertEquals("[HelpOp] Hi", plain(msg.get("helpop.sent")));
    }

    @Test
    void namedPrefixColorsCorrectlyInsteadOfShowingLiteralCodes() throws IOException {
        MessagesFile msg = load(yaml("""
                prefix:
                  helpop: "&c[HelpOp]&r "
                helpop:
                  sent: "%helpop-prefix%Hi"
                """));
        Component rendered = msg.get("helpop.sent");
        String text = plain(rendered);
        assertFalse(text.contains("&c") || text.contains("&r"), "prefix color codes must render as color; got: " + text);
        assertTrue(text.contains("[HelpOp] Hi"), "got: " + text);
        assertTrue(componentTreeContainsColor(rendered, NamedTextColor.RED),
                "the prefix must actually carry the RED color, not just avoid showing literal &c codes "
                        + "(plain-text alone can't tell 'colored correctly' apart from 'color silently lost')");
    }

    @Test
    void namedPrefixDoesNotInterfereWithCallerPlaceholders() throws IOException {
        MessagesFile msg = load(yaml("""
                prefix:
                  helpop: "[HelpOp] "
                helpop:
                  cooldown: "%helpop-prefix%Wait %seconds%s"
                """));
        assertEquals("[HelpOp] Wait 5s", plain(msg.get("helpop.cooldown", Map.of("seconds", "5"))));
    }

    @Test
    void namedPrefixAutoSubstitutesInSend() throws IOException {
        MessagesFile msg = load(yaml("""
                prefix:
                  staffchat: "[SC] "
                staffchat:
                  toggled-on: "%staffchat-prefix%On!"
                """));
        Cap a = new Cap();
        msg.send(a, "staffchat.toggled-on");
        assertEquals("[SC] On!", plain(a.received.get(0)));
    }

    @Test
    void namedPrefixAutoSubstitutesInBroadcast() throws IOException {
        MessagesFile msg = load(yaml("""
                prefix:
                  announcement: "[Ann] "
                announcement:
                  sent: "%announcement-prefix%Done"
                """));
        Cap a1 = new Cap(), a2 = new Cap();
        msg.broadcast(List.of(a1, a2), "announcement.sent");
        assertEquals("[Ann] Done", plain(a1.received.get(0)));
        assertEquals("[Ann] Done", plain(a2.received.get(0)));
    }

    @Test
    void namedPrefixAutoSubstitutesInFormatChatAlongsideCallerComponents() throws IOException {
        MessagesFile msg = load(yaml("""
                prefix:
                  staffchat: "[SC] "
                staffchat:
                  format: "%staffchat-prefix%%player%: %message%"
                """));
        Component result = msg.formatChat("staffchat.format", Map.of(
                "player", Component.text("Alice"),
                "message", Component.text("hi")));
        assertEquals("[SC] Alice: hi", plain(result));
    }

    @Test
    void namedPrefixAutoSubstitutesInGetList() throws IOException {
        MessagesFile msg = load(yaml("""
                prefix:
                  helpop: "[HelpOp] "
                helpop:
                  format:
                    - "%helpop-prefix%line one"
                    - "line two, no token"
                """));
        List<Component> list = msg.getList("helpop.format");
        assertEquals("[HelpOp] line one", plain(list.get(0)));
        assertEquals("line two, no token", plain(list.get(1)));
    }

    @Test
    void namedPrefixByNameAccessor() throws IOException {
        MessagesFile msg = load(yaml("""
                prefix:
                  helpop: "[HelpOp] "
                  staffchat: "[SC] "
                """));
        assertEquals("[HelpOp] ", msg.prefix("helpop"));
        assertEquals("[SC] ", msg.prefix("staffchat"));
    }

    @Test
    void namedPrefixByNameMissingReturnsEmptyString() throws IOException {
        MessagesFile msg = load(yaml("prefix:\n  helpop: \"[HelpOp] \""));
        assertEquals("", msg.prefix("nonexistent"));
    }

    @Test
    void namedPrefixMapFormDoesNotBreakLegacyFlatPrefixToken() throws IOException {
        // %prefix% has no defined meaning when prefix: is a map (which section would it mean?) —
        // it must stay predictable ("" — same as today, not a regression) rather than throw or
        // pick an arbitrary section.
        MessagesFile msg = load(yaml("""
                prefix:
                  helpop: "[HelpOp] "
                greet: "%prefix%Hi"
                """));
        assertEquals("Hi", plain(msg.get("greet")));
    }

    @Test
    void namedPrefixAutoSubstitutesInSendTitle() throws IOException {
        MessagesFile msg = load(yaml("""
                prefix:
                  helpop: "[HelpOp] "
                helpop:
                  title:
                    - "%helpop-prefix%Title"
                """));
        TitleAudience a = new TitleAudience();
        msg.sendTitle(a, "helpop.title");
        assertEquals("[HelpOp] Title", plain(a.lastTitle.title()));
    }

    @Test
    void namedPrefixAutoSubstitutesInSendActionBar() throws IOException {
        MessagesFile msg = load(yaml("""
                prefix:
                  helpop: "[HelpOp] "
                helpop:
                  bar: "%helpop-prefix%Bar"
                """));
        ActionBarAudience a = new ActionBarAudience();
        msg.sendActionBar(a, "helpop.bar");
        assertEquals("[HelpOp] Bar", plain(a.lastActionBar));
    }

    private static final class TitleAudience implements Audience {
        net.kyori.adventure.title.Title lastTitle;
        @Override public void showTitle(net.kyori.adventure.title.Title title) { lastTitle = title; }
    }

    private static final class ActionBarAudience implements Audience {
        Component lastActionBar;
        @Override public void sendActionBar(Component message) { lastActionBar = message; }
    }

    @Test
    void namedPrefixReloadPicksUpChangedPrefixValue() throws IOException {
        Path f = yaml("""
                prefix:
                  helpop: "[V1] "
                helpop:
                  sent: "%helpop-prefix%Hi"
                """);
        MessagesFile msg = load(f);
        assertEquals("[V1] Hi", plain(msg.get("helpop.sent")));
        Files.writeString(f, """
                prefix:
                  helpop: "[V2] "
                helpop:
                  sent: "%helpop-prefix%Hi"
                """);
        msg.reload();
        assertEquals("[V2] Hi", plain(msg.get("helpop.sent")));
    }

    // ── unmatched %<name>-prefix% token detection ───────────────────────────────
    //
    // Catches the exact failure mode that motivated this whole feature: a template author
    // writes %foo-prefix% (typo, or the prefix.foo entry was never added) and the token
    // silently stays literal with no signal anything is wrong. Only checked when at least
    // one named prefix is actually defined — a plugin not using this feature at all must
    // never be warned about a coincidentally "-prefix"-suffixed placeholder of its own.

    private static long unmatchedPrefixWarns(CapturingLogger log) {
        return log.warns().stream()
                .filter(e -> e.pattern != null && e.pattern.contains("no matching 'prefix."))
                .count();
    }

    @Test
    void warnsOnceOnTokenWithNoMatchingNamedPrefix() throws IOException {
        CapturingLogger log = new CapturingLogger();
        Path f = yaml("""
                prefix:
                  helpop: "[HelpOp] "
                greet: "%typo-prefix%Hi"
                """);
        MessagesFile.load(f, null, log);
        assertEquals(1, unmatchedPrefixWarns(log),
                "a %typo-prefix% token with no prefix.typo entry must be flagged at load time");
    }

    @Test
    void warnsOnUnmatchedTokenEvenWhenNameContainsHyphens() throws IOException {
        // The substitution regex (TextUtil.PLACEHOLDER) allows hyphens in a placeholder name,
        // so a section can legitimately be named "staff-chat". The typo-detection regex must
        // recognize the same name shape, or a genuine typo in a hyphenated name goes undetected.
        CapturingLogger log = new CapturingLogger();
        Path f = yaml("""
                prefix:
                  helpop: "[HelpOp] "
                greet: "%staff-chat-prefix%Hi"
                """);
        MessagesFile.load(f, null, log);
        assertEquals(1, unmatchedPrefixWarns(log),
                "a hyphenated %staff-chat-prefix% token with no prefix.staff-chat entry must still be flagged");
    }

    @Test
    void doesNotWarnWhenHyphenatedNameMatchesARealNamedPrefix() throws IOException {
        CapturingLogger log = new CapturingLogger();
        Path f = yaml("""
                prefix:
                  staff-chat: "[SC] "
                greet: "%staff-chat-prefix%Hi"
                """);
        MessagesFile.load(f, null, log);
        assertEquals(0, unmatchedPrefixWarns(log));
    }

    @Test
    void doesNotWarnWhenTokenMatchesARealNamedPrefix() throws IOException {
        CapturingLogger log = new CapturingLogger();
        Path f = yaml("""
                prefix:
                  helpop: "[HelpOp] "
                helpop:
                  sent: "%helpop-prefix%Hi"
                """);
        MessagesFile.load(f, null, log);
        assertEquals(0, unmatchedPrefixWarns(log));
    }

    @Test
    void doesNotWarnAboutPrefixLikeTokenWhenFeatureIsUnused() throws IOException {
        // No `prefix:` map at all (legacy flat-string or no prefix key) — the multi-prefix
        // feature isn't in play, so a coincidentally "-prefix"-suffixed placeholder some
        // caller supplies at runtime (e.g. %custom-prefix%) must not be treated as a typo.
        CapturingLogger log = new CapturingLogger();
        Path f = yaml("""
                prefix: "[Flat] "
                greet: "%custom-prefix%Hi"
                """);
        MessagesFile.load(f, null, log);
        assertEquals(0, unmatchedPrefixWarns(log));
    }

    @Test
    void unmatchedPrefixTokenInAListValueIsAlsoDetected() throws IOException {
        CapturingLogger log = new CapturingLogger();
        Path f = yaml("""
                prefix:
                  helpop: "[HelpOp] "
                helpop:
                  format:
                    - "%typo-prefix%line one"
                    - "line two"
                """);
        MessagesFile.load(f, null, log);
        assertEquals(1, unmatchedPrefixWarns(log));
    }

    @Test
    void sameUnmatchedTokenAcrossMultipleKeysWarnsOnlyOnce() throws IOException {
        CapturingLogger log = new CapturingLogger();
        Path f = yaml("""
                prefix:
                  helpop: "[HelpOp] "
                a: "%typo-prefix%A"
                b: "%typo-prefix%B"
                """);
        MessagesFile.load(f, null, log);
        assertEquals(1, unmatchedPrefixWarns(log), "the same missing name must warn once per load, not once per occurrence");
    }

    @Test
    void unmatchedPrefixTokenWarningReArmsOnReload() throws IOException {
        CapturingLogger log = new CapturingLogger();
        Path f = yaml("""
                prefix:
                  helpop: "[HelpOp] "
                greet: "%typo-prefix%Hi"
                """);
        MessagesFile msg = MessagesFile.load(f, null, log);
        assertEquals(1, unmatchedPrefixWarns(log));
        msg.reload();
        assertEquals(2, unmatchedPrefixWarns(log), "an unfixed typo must be flagged again on every reload, not just once ever");
    }
}
