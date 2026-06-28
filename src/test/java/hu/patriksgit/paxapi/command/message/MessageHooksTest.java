package hu.patriksgit.paxapi.command.message;

import hu.patriksgit.paxapi.text.MessagesFile;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageHooksTest {

    private MessagesFile load() throws Exception {
        Path tmp = Files.createTempFile("messages", ".yml");
        String yaml = "general:\n  no-permission: 'NO'\nadmin:\n  unknown-subcommand: 'UNK %sub%'\n  player-only: 'PO'\n";
        Files.writeString(tmp, yaml);
        return MessagesFile.load(tmp, new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
            LoggerFactory.getLogger("test"));
    }

    @Test void deniedSendsNoPermissionKey() throws Exception {
        MessagesFile messages = load();
        Audience audience = mock(Audience.class);
        MessageHooks<Audience> hooks = MessageHooks.of(messages);
        hooks.denied().accept(audience);
        verify(audience).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test void unknownPassesSubPlaceholder() throws Exception {
        MessageHooks<Audience> hooks = MessageHooks.of(load());
        Audience audience = mock(Audience.class);
        hooks.unknown().accept(audience, "bogus");
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(audience).sendMessage(captor.capture());
        String plain = PlainTextComponentSerializer.plainText().serialize(captor.getValue());
        // %sub% must actually be substituted into the rendered message, not ignored
        assertTrue(plain.contains("bogus"),
            "Rendered unknown-subcommand message must contain the sub value; got: " + plain);
    }

    @Test void playerOnlySendsPlayerOnlyKey() throws Exception {
        MessageHooks<Audience> hooks = MessageHooks.of(load());
        Audience audience = mock(Audience.class);
        hooks.playerOnly().accept(audience);
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(audience).sendMessage(captor.capture());
        String plain = PlainTextComponentSerializer.plainText().serialize(captor.getValue());
        // admin.player-only resolves to 'PO' in the test YAML
        assertEquals("PO", plain);
    }
}
