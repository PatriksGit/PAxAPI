package hu.patriksgit.paxapi.command;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CommandDispatcherExecuteTest {

    private CommandDispatcher<FakeSender> dispatcher(CommandSpec<FakeSender> spec) {
        return new CommandDispatcher<>(spec, FakeSender.ADAPTER);
    }

    @Test void noArgsRunsRootHandler() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("mineauth")
            .handler(ctx -> ctx.sender().events.add("help"))
            .sub("reload", ctx -> ctx.sender().events.add("reload"))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "mineauth", new String[0]);
        assertEquals(java.util.List.of("help"), s.events);
    }

    @Test void matchedSubcommandRunsItsHandlerWithRemainingArgs() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("mineauth")
            .group("maintenance", m -> m.sub("on", ctx ->
                ctx.sender().events.add("on:" + String.join(",", ctx.args()) + ":path=" + ctx.path())))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "mineauth", new String[]{"maintenance", "on", "extra"});
        assertEquals(java.util.List.of("on:extra:path=[maintenance, on]"), s.events);
    }

    @Test void permissionDenialFiresInheritedOnDenied() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("mineauth")
            .permission("mineauth.admin")
            .onDenied(s -> s.events.add("denied"))
            .sub("reload", ctx -> ctx.sender().events.add("reload"))
            .build();
        FakeSender s = FakeSender.player();          // no permission
        dispatcher(spec).execute(s, "mineauth", new String[]{"reload"});
        assertEquals(java.util.List.of("denied"), s.events);
    }

    @Test void unknownSubcommandFiresOnUnknownWithToken() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("mineauth")
            .onUnknown((s, tok) -> s.events.add("unknown:" + tok))
            .sub("reload", ctx -> {})
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "mineauth", new String[]{"bogus"});
        assertEquals(java.util.List.of("unknown:bogus"), s.events);
    }

    @Test void requirementGateHidesAndFails() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("mineauth")
            .group("setspawn", sub -> sub
                .requires(s -> false, s -> s.events.add("req-fail"))
                .handler(ctx -> ctx.sender().events.add("setspawn")))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "mineauth", new String[]{"setspawn"});
        assertEquals(java.util.List.of("req-fail"), s.events);
    }

    @Test void playerOnlyBlocksConsole() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("mineauth")
            .group("setspawn", sub -> sub
                .playerOnly(s -> s.events.add("player-only"))
                .handler(ctx -> ctx.sender().events.add("setspawn")))
            .build();
        FakeSender console = FakeSender.console();
        dispatcher(spec).execute(console, "mineauth", new String[]{"setspawn"});
        assertEquals(java.util.List.of("player-only"), console.events);
    }

    @Test void handlerExceptionRoutesToOnError() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .onError((s, t) -> s.events.add("error:" + t.getMessage()))
            .handler(ctx -> { throw new IllegalStateException("boom"); })
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "x", new String[0]);
        assertEquals(java.util.List.of("error:boom"), s.events);
    }

    // FIX 1: subcommand aliases resolve via execute
    @Test void subcommandAliasResolvesViaExecute() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("mineauth")
            .group("maintenance", m -> m
                .aliases("maint")
                .sub("on", ctx -> ctx.sender().events.add("on")))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "mineauth", new String[]{"maint", "on"});
        assertEquals(java.util.List.of("on"), s.events);
    }

    // FIX 1: canonical name wins when alias collides with another child's canonical name
    @Test void canonicalNameWinsOverAliasCollision() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("cmd")
            .sub("foo", ctx -> ctx.sender().events.add("canonical-foo"))
            .group("bar", b -> b.aliases("foo").handler(ctx -> ctx.sender().events.add("alias-bar")))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "cmd", new String[]{"foo"});
        assertEquals(java.util.List.of("canonical-foo"), s.events);
    }

    // FIX 3: playerOnly on intermediate (group) node blocks console before reaching handler
    @Test void playerOnlyOnGroupNodeBlocksConsoleBeforeHandler() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("mineauth")
            .group("setspawn", s -> s
                .playerOnly(snd -> snd.events.add("po"))
                .sub("here", ctx -> ctx.sender().events.add("here")))
            .build();
        FakeSender console = FakeSender.console();
        dispatcher(spec).execute(console, "mineauth", new String[]{"setspawn", "here"});
        assertEquals(java.util.List.of("po"), console.events);
    }

    // FIX 4: throwing requirement predicate routes to onError, not propagated
    @Test void throwingRequirementRoutesToOnError() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .onError((snd, t) -> snd.events.add("err"))
            .requires(s -> { throw new RuntimeException("x"); }, snd -> snd.events.add("req-fail"))
            .handler(ctx -> ctx.sender().events.add("handler"))
            .build();
        FakeSender s = FakeSender.player();
        assertDoesNotThrow(() -> dispatcher(spec).execute(s, "x", new String[0]));
        assertEquals(java.util.List.of("err"), s.events);
    }

    // FIX 4: throwing requirement with no onError is swallowed, not propagated
    @Test void throwingRequirementWithNoOnErrorIsSwallowed() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .requires(s -> { throw new RuntimeException("x"); }, snd -> {})
            .handler(ctx -> ctx.sender().events.add("handler"))
            .build();
        FakeSender s = FakeSender.player();
        assertDoesNotThrow(() -> dispatcher(spec).execute(s, "x", new String[0]));
        assertTrue(s.events.isEmpty());
    }

    // handler bugs must NOT be silently swallowed — they propagate so the platform can log them
    @Test void handlerExceptionWithoutOnErrorPropagates() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .handler(ctx -> { throw new IllegalStateException("boom"); })
            .build();
        FakeSender s = FakeSender.player();
        assertThrows(RuntimeException.class, () -> dispatcher(spec).execute(s, "x", new String[0]));
    }

    // a custom SenderAdapter is caller-supplied (e.g. an async permission-plugin lookup) and
    // must be guarded the same way requirement() already is, not crash execute() uncaught.
    @Test void throwingHasPermissionRoutesToOnError() {
        SenderAdapter<FakeSender> throwing = new SenderAdapter<>() {
            public boolean hasPermission(FakeSender s, String perm) { throw new RuntimeException("perm-check-broken"); }
            public boolean isPlayer(FakeSender s) { return s.player; }
        };
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .permission("x.use")
            .onError((snd, t) -> snd.events.add("err"))
            .handler(ctx -> ctx.sender().events.add("handler"))
            .build();
        FakeSender s = FakeSender.player();
        assertDoesNotThrow(() -> new CommandDispatcher<>(spec, throwing).execute(s, "x", new String[0]));
        assertEquals(java.util.List.of("err"), s.events);
    }

    @Test void throwingHasPermissionWithNoOnErrorIsSwallowed() {
        SenderAdapter<FakeSender> throwing = new SenderAdapter<>() {
            public boolean hasPermission(FakeSender s, String perm) { throw new RuntimeException("perm-check-broken"); }
            public boolean isPlayer(FakeSender s) { return s.player; }
        };
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .permission("x.use")
            .handler(ctx -> ctx.sender().events.add("handler"))
            .build();
        FakeSender s = FakeSender.player();
        assertDoesNotThrow(() -> new CommandDispatcher<>(spec, throwing).execute(s, "x", new String[0]));
        assertTrue(s.events.isEmpty());
    }

    @Test void throwingIsPlayerRoutesToOnError() {
        SenderAdapter<FakeSender> throwing = new SenderAdapter<>() {
            public boolean hasPermission(FakeSender s, String perm) { return true; }
            public boolean isPlayer(FakeSender s) { throw new RuntimeException("player-check-broken"); }
        };
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .playerOnly(snd -> {})
            .onError((snd, t) -> snd.events.add("err"))
            .handler(ctx -> ctx.sender().events.add("handler"))
            .build();
        FakeSender s = FakeSender.player();
        assertDoesNotThrow(() -> new CommandDispatcher<>(spec, throwing).execute(s, "x", new String[0]));
        assertEquals(java.util.List.of("err"), s.events);
    }

    @Test void throwingIsPlayerWithNoOnErrorIsSwallowed() {
        SenderAdapter<FakeSender> throwing = new SenderAdapter<>() {
            public boolean hasPermission(FakeSender s, String perm) { return true; }
            public boolean isPlayer(FakeSender s) { throw new RuntimeException("player-check-broken"); }
        };
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .playerOnly(snd -> {})
            .handler(ctx -> ctx.sender().events.add("handler"))
            .build();
        FakeSender s = FakeSender.player();
        assertDoesNotThrow(() -> new CommandDispatcher<>(spec, throwing).execute(s, "x", new String[0]));
        assertTrue(s.events.isEmpty());
    }

    // the onError callback is caller-supplied and must not itself be able to crash execute()
    // uncaught — every other callback invocation in the dispatcher is guarded this way already.
    @Test void throwingOnErrorHandlerDoesNotPropagate() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .onError((snd, t) -> { throw new RuntimeException("onError itself is broken"); })
            .handler(ctx -> { throw new IllegalStateException("boom"); })
            .build();
        FakeSender s = FakeSender.player();
        assertDoesNotThrow(() -> dispatcher(spec).execute(s, "x", new String[0]));
    }

    @Test void cooldownBlocksSecondCallWithinWindow() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "x", new String[0]);
        dispatcher(spec).execute(s, "x", new String[0]);
        assertEquals(java.util.List.of("handled", "cooldown"), s.events);
    }

    @Test void cooldownAllowsCallAfterWindowElapses() throws InterruptedException {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .cooldown(tracker, () -> Duration.ofMillis(50), (s, remaining) -> s.events.add("cooldown"))
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "x", new String[0]);
        Thread.sleep(80);
        dispatcher(spec).execute(s, "x", new String[0]);
        assertEquals(java.util.List.of("handled", "handled"), s.events);
    }

    // Gate order: permission fails before the cooldown line is ever reached, so a denied
    // attempt must leave the cooldown completely fresh for the next (permitted) attempt.
    @Test void cooldownNotConsumedByPermissionDenial() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .permission("x.use")
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender s = FakeSender.player(); // lacks x.use
        dispatcher(spec).execute(s, "x", new String[0]); // permission gate fails first; cooldown line never reached
        s.permissions.add("x.use");
        dispatcher(spec).execute(s, "x", new String[0]); // now passes permission; cooldown must still be fresh
        assertEquals(java.util.List.of("handled"), s.events);
    }

    // Same gate-order concern as above, but for playerOnly. The denied sender deliberately gets
    // a non-null identity (a combination no REAL adapter ever produces — only players have one)
    // purely to make this test meaningful: with the default null identity, a wrongly-ordered
    // cooldown check would be masked by the null-identity exemption instead of being caught here.
    @Test void cooldownNotConsumedByPlayerOnlyDenial() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .playerOnly(s -> {})
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender nonPlayer = FakeSender.console();
        nonPlayer.id = java.util.UUID.randomUUID().toString();
        dispatcher(spec).execute(nonPlayer, "x", new String[0]); // playerOnly fails first; cooldown line never reached

        FakeSender player = FakeSender.player();
        player.id = nonPlayer.id; // same tracker key as the denied attempt above
        dispatcher(spec).execute(player, "x", new String[0]); // must still succeed
        assertEquals(java.util.List.of("handled"), player.events);
    }

    // "Unknown subcommand never consumes it" is a routing concern, not a same-node code-order
    // concern: the cooldown-gated leaf is simply never VISITED when an unrelated top-level token
    // doesn't match any child, so its cooldown is never evaluated at all.
    @Test void cooldownNotConsumedByUnknownSubcommand() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .group("known", g -> g
                .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
                .handler(ctx -> ctx.sender().events.add("handled")))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "x", new String[]{"bogus"}); // unknown top-level token; "known" leaf never visited
        dispatcher(spec).execute(s, "x", new String[]{"known"}); // first real visit to the cooldown-gated leaf
        assertEquals(java.util.List.of("handled"), s.events);
    }

    @Test void cooldownConsumedEvenWhenHandlerThrows() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
            .onError((s, t) -> s.events.add("error"))
            .handler(ctx -> { throw new IllegalStateException("boom"); })
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "x", new String[0]); // handler throws, routed to onError
        dispatcher(spec).execute(s, "x", new String[0]); // cooldown still active even though the first handler failed
        assertEquals(java.util.List.of("error", "cooldown"), s.events);
    }

    @Test void nullIdentityExemptsSenderFromCooldown() {
        CooldownTracker tracker = new CooldownTracker();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> s.events.add("cooldown"))
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender s = FakeSender.player();
        s.id = null; // simulate a sender with no resolvable identity (e.g. console/RCON in real adapters)
        dispatcher(spec).execute(s, "x", new String[0]);
        dispatcher(spec).execute(s, "x", new String[0]); // would be blocked if not exempt
        assertEquals(java.util.List.of("handled", "handled"), s.events);
    }

    @Test void cooldownOnCooldownReceivesSenderAndRemaining() {
        CooldownTracker tracker = new CooldownTracker();
        java.util.List<Duration> seen = new java.util.ArrayList<>();
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .cooldown(tracker, () -> Duration.ofMinutes(10), (s, remaining) -> { s.events.add("cooldown"); seen.add(remaining); })
            .handler(ctx -> ctx.sender().events.add("handled"))
            .build();
        FakeSender s = FakeSender.player();
        dispatcher(spec).execute(s, "x", new String[0]);
        dispatcher(spec).execute(s, "x", new String[0]);
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).compareTo(Duration.ZERO) > 0, "remaining must be positive, got " + seen.get(0));
        assertTrue(seen.get(0).compareTo(Duration.ofMinutes(10)) <= 0, "remaining must not exceed the configured cooldown, got " + seen.get(0));
    }
}
