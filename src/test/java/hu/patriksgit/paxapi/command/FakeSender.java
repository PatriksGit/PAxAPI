package hu.patriksgit.paxapi.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Test sender: records messages, holds permissions + player flag. */
final class FakeSender {
    final Set<String> permissions = new HashSet<>();
    boolean player;
    final List<String> events = new ArrayList<>();

    static FakeSender player(String... perms) { return make(true, perms); }
    static FakeSender console(String... perms) { return make(false, perms); }
    private static FakeSender make(boolean player, String... perms) {
        FakeSender s = new FakeSender();
        s.player = player;
        for (String p : perms) s.permissions.add(p);
        return s;
    }

    static final SenderAdapter<FakeSender> ADAPTER = new SenderAdapter<>() {
        public boolean hasPermission(FakeSender s, String perm) { return s.permissions.contains(perm); }
        public boolean isPlayer(FakeSender s) { return s.player; }
    };
}
