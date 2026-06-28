ackage hu.patriksgit.paxapi.command.paper;

import hu.patriksgit.paxapi.command.CommandDispatcher;
import hu.patriksgit.paxapi.command.CommandSpec;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registers a {@link CommandSpec} as a Bukkit command + tab-completer. */
public final class PaperCommands {
    private PaperCommands() {}

    /**
     * Registers the given spec as a Bukkit command and tab-completer.
     * Any aliases defined via {@link CommandSpec.Builder#aliases(String...)} are applied
     * to the Paper command map, equivalent to declaring them in {@code plugin.yml}.
     */
    public static void register(JavaPlugin plugin, CommandSpec<CommandSender> spec) {
        PluginCommand cmd = plugin.getCommand(spec.name());
        if (cmd == null) {
            throw new IllegalStateException("Command '" + spec.name()
                + "' is not declared in plugin.yml — add it under `commands:` before registering.");
        }
        CommandDispatcher<CommandSender> dispatcher = new CommandDispatcher<>(spec, PaperSenderAdapter.INSTANCE);
        cmd.setExecutor((sender, command, label, args) -> { dispatcher.execute(sender, label, args); return true; });
        cmd.setTabCompleter((TabCompleter) (sender, command, alias, args) -> dispatcher.complete(sender, args));
        if (!spec.aliases().isEmpty()) {
            applyAliases(plugin, cmd, spec.aliases());
        }
    }

    /**
     * Replaces the alias list for an already-registered command.
     * Safe to call multiple times — each call replaces previous aliases rather than accumulating.
     * Intended for live-reload scenarios where aliases come from configuration.
     *
     * @param commandName the name declared under {@code commands:} in {@code plugin.yml}
     * @param aliases     the new alias list; empty list removes all aliases
     */
    public static void applyAliases(JavaPlugin plugin, String commandName, List<String> aliases) {
        PluginCommand cmd = plugin.getCommand(commandName);
        if (cmd == null) return;
        applyAliases(plugin, cmd, aliases);
    }

    private static void applyAliases(JavaPlugin plugin, PluginCommand cmd, List<String> aliases) {
        SimpleCommandMap smap = (SimpleCommandMap) plugin.getServer().getCommandMap();
        Map<String, Command> known = smap.getKnownCommands();
        String prefix = plugin.getDescription().getName().toLowerCase(Locale.ROOT);
        String canonical = cmd.getName().toLowerCase(Locale.ROOT);

        // Remove stale alias entries (preserve canonical name and its prefixed form).
        // Collect keys first — Paper 1.20.6+ may return getKnownCommands() as a view
        // whose entry-set iterator does not support remove(), causing UnsupportedOperationException
        // if we call entrySet().removeIf() directly.
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Command> e : known.entrySet()) {
            if (e.getValue() == cmd
                    && !e.getKey().equals(canonical)
                    && !e.getKey().equals(prefix + ":" + canonical)) {
                stale.add(e.getKey());
            }
        }
        stale.forEach(known::remove);

        cmd.setAliases(new ArrayList<>(aliases));

        for (String alias : aliases) {
            String lc = alias.toLowerCase(Locale.ROOT);
            known.put(lc, cmd);
            known.put(prefix + ":" + lc, cmd);
        }

        // Rebuild the Brigadier command tree so Paper 1.13+ clients see the new aliases.
        // syncCommands() lives on CraftServer, not the public Server interface — use reflection.
        try {
            plugin.getServer().getClass().getMethod("syncCommands").invoke(plugin.getServer());
        } catch (ReflectiveOperationException ignored) { /* not available on this build */ }
    }
}
