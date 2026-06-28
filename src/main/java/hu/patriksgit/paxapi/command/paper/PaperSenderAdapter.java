package hu.patriksgit.paxapi.command.paper;

import hu.patriksgit.paxapi.command.SenderAdapter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PaperSenderAdapter implements SenderAdapter<CommandSender> {
    public static final PaperSenderAdapter INSTANCE = new PaperSenderAdapter();
    private PaperSenderAdapter() {}
    @Override public boolean hasPermission(CommandSender sender, String permission) { return sender.hasPermission(permission); }
    @Override public boolean isPlayer(CommandSender sender) { return sender instanceof Player; }
}
