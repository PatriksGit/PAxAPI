package hu.patriksgit.paxapi.command.velocity;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import hu.patriksgit.paxapi.command.CommandDispatcher;
import hu.patriksgit.paxapi.command.CommandSpec;

import java.util.List;

/** Registers a {@link CommandSpec} as a Velocity {@link SimpleCommand}. */
public final class VelocityCommands {
    private VelocityCommands() {}

    /**
     * Registers the given spec as a Velocity command. To remove it again (plugin
     * shutdown or live reload) call {@link #unregister(ProxyServer, CommandSpec)}.
     */
    public static void register(ProxyServer proxy, Object plugin, CommandSpec<CommandSource> spec) {
        CommandDispatcher<CommandSource> dispatcher = new CommandDispatcher<>(spec, VelocitySenderAdapter.INSTANCE);
        CommandManager mgr = proxy.getCommandManager();
        CommandMeta meta = mgr.metaBuilder(spec.name())
            .aliases(spec.aliases().toArray(new String[0]))
            .plugin(plugin)
            .build();
        mgr.register(meta, new SimpleCommand() {
            @Override public void execute(Invocation invocation) {
                dispatcher.execute(invocation.source(), invocation.alias(), invocation.arguments());
            }
            @Override public List<String> suggest(Invocation invocation) {
                return dispatcher.complete(invocation.source(), invocation.arguments());
            }
            @Override public boolean hasPermission(Invocation invocation) {
                return true; // dispatcher owns permission gating + onDenied messaging
            }
        });
    }

    /** Unregisters a previously {@link #register registered} command (plugin shutdown / live reload). */
    public static void unregister(ProxyServer proxy, CommandSpec<CommandSource> spec) {
        proxy.getCommandManager().unregister(spec.name());
    }
}
