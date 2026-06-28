ackage hu.patriksgit.paxapi.command.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import hu.patriksgit.paxapi.command.SenderAdapter;

public final class VelocitySenderAdapter implements SenderAdapter<CommandSource> {
    public static final VelocitySenderAdapter INSTANCE = new VelocitySenderAdapter();
    private VelocitySenderAdapter() {}
    @Override public boolean hasPermission(CommandSource sender, String permission) { return sender.hasPermission(permission); }
    @Override public boolean isPlayer(CommandSource sender) { return sender instanceof Player; }
}
