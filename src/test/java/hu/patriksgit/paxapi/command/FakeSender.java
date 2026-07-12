package hu.patriksgit.paxapi.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Test sender: records messages, holds permissions + player flag. */
final class FakeSender {
    final Set<String> permissions = new HashSet<>();
    boolean player;
    // Cooldown identity: UUID string for players, null for console (mirrors the real adapters).
    // Mutable and package-visible so individual tests can force an unusual combination (e.g. a
    // non-player WITH an identity) to isolate one specific gate-ordering behavior.
    String id;
    final List<String> events = new ArrayList<>();

    static FakeSender player(String... perms) { return make(true, UUID.randomUUID().toString(), perms); }
    static FakeSender console(String... perms) { return make(false, null, perms); }
    private static FakeSender make(boolean player, String id, String... perms) {
        FakeSender s = new FakeSender();
        s.player = player;
        s.id = id;
        for (String p : perms) s.permissions.add(p);
        return s;
    }

    static final SenderAdapter<FakeSender> ADAPTER = new SenderAdapter<>() {
        public boolean hasPermission(FakeSender s, String perm) { return s.permissions.contains(perm); }
        public boolean isPlayer(FakeSender s) { return s.player; }
        public String identity(FakeSender s) { return s.id; }
    };
}
