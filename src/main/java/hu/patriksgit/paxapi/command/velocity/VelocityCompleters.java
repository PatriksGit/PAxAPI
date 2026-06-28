package hu.patriksgit.paxapi.command.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import hu.patriksgit.paxapi.command.ArgumentCompleter;

import java.util.ArrayList;
import java.util.List;

public final class VelocityCompleters {
    private VelocityCompleters() {}

    public static ArgumentCompleter<CommandSource> onlinePlayers(ProxyServer proxy) {
        return (sender, args, current) -> {
            List<String> names = new ArrayList<>();
            // Velocity has no built-in vanish/canSee visibility API, so there is
            // nothing to filter on here; all online players are returned.
            proxy.getAllPlayers().forEach(p -> names.add(p.getUsername()));
            return names;
        };
    }
}
