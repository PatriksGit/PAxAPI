package hu.patriksgit.paxapi.command.message;

import hu.patriksgit.paxapi.text.MessagesFile;
import net.kyori.adventure.audience.Audience;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Bridges a TextUtilAPI {@link MessagesFile} to CommandBuilder hook consumers.
 * The core never references this class — pass the produced consumers into the builder.
 */
public final class MessageHooks<S extends Audience> {

    private final MessagesFile messages;
    private String noPermissionKey = "general.no-permission";
    private String unknownKey = "admin.unknown-subcommand";
    private String playerOnlyKey = "admin.player-only";

    private MessageHooks(MessagesFile messages) { this.messages = messages; }

    public static <S extends Audience> MessageHooks<S> of(MessagesFile messages) {
        return new MessageHooks<>(messages);
    }

    public MessageHooks<S> noPermissionKey(String key) { this.noPermissionKey = key; return this; }
    public MessageHooks<S> unknownKey(String key) { this.unknownKey = key; return this; }
    public MessageHooks<S> playerOnlyKey(String key) { this.playerOnlyKey = key; return this; }

    public Consumer<S> denied() { return s -> messages.send(s, noPermissionKey); }
    public BiConsumer<S, String> unknown() { return (s, sub) -> messages.send(s, unknownKey, Map.of("sub", sub)); }
    public Consumer<S> playerOnly() { return s -> messages.send(s, playerOnlyKey); }
}
