package hu.patriksgit.paxapi.text;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
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
}
