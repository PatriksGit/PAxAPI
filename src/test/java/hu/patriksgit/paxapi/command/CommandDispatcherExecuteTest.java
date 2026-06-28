ackage hu.patriksgit.paxapi.command;

import org.junit.jupiter.api.Test;
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
}
