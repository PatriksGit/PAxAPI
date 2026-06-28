package hu.patriksgit.paxapi.command.paper;

import hu.patriksgit.paxapi.command.ArgumentCompleter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class PaperCompleters {
    private PaperCompleters() {}

    /**
     * Completer for names of online players visible to the sender.
     *
     * <p>Must be used only on the synchronous Paper tab-complete path: it touches
     * Bukkit player state (notably {@link Player#canSee(Player)}), which are
     * main-thread-only APIs. Iterates over a snapshot copy of the online players
     * to avoid issues with the live view returned by {@link Bukkit#getOnlinePlayers()}.
     */
    public static ArgumentCompleter<CommandSender> onlinePlayers() {
        return (sender, args, current) -> {
            List<String> names = new ArrayList<>();
            Player senderPlayer = sender instanceof Player ? (Player) sender : null;
            new ArrayList<>(Bukkit.getOnlinePlayers()).forEach(p -> {
                if (senderPlayer == null || senderPlayer.canSee(p)) names.add(p.getName());
            });
            return names;
        };
    }
}
