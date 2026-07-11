package hu.patriksgit.paxapi.command;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommandDispatcherCompleteTest {

    private CommandDispatcher<FakeSender> dispatcher(CommandSpec<FakeSender> spec) {
        return new CommandDispatcher<>(spec, FakeSender.ADAPTER);
    }

    private CommandSpec<FakeSender> adminSpec() {
        return CommandSpec.<FakeSender>root("mineauth")
            .permission("mineauth.admin")
            .sub("reload", ctx -> {})
            .group("maintenance", m -> m.sub("on", c -> {}).sub("off", c -> {}).sub("status", c -> {}))
            .group("setspawn", sub -> sub
                .requires(s -> s.permissions.contains("auth.backend"), s -> {})
                .handler(ctx -> {}))
            .build();
    }

    @Test void firstTokenSuggestsAccessibleSubcommandsInOrder() {
        FakeSender s = FakeSender.player("mineauth.admin"); // no auth.backend → setspawn hidden
        assertEquals(List.of("reload", "maintenance"), dispatcher(adminSpec()).complete(s, new String[]{""}));
    }

    @Test void requirementGatedSubcommandShownWhenRequirementMet() {
        FakeSender s = FakeSender.player("mineauth.admin", "auth.backend");
        assertEquals(List.of("reload", "maintenance", "setspawn"), dispatcher(adminSpec()).complete(s, new String[]{""}));
    }

    @Test void prefixFiltersSubcommands() {
        FakeSender s = FakeSender.player("mineauth.admin");
        assertEquals(List.of("reload"), dispatcher(adminSpec()).complete(s, new String[]{"re"}));
    }

    @Test void noPermissionYieldsEmpty() {
        FakeSender s = FakeSender.player(); // lacks mineauth.admin
        assertEquals(List.of(), dispatcher(adminSpec()).complete(s, new String[]{""}));
    }

    @Test void nestedSubcommandSuggestions() {
        FakeSender s = FakeSender.player("mineauth.admin");
        assertEquals(List.of("on", "off", "status"),
            dispatcher(adminSpec()).complete(s, new String[]{"maintenance", ""}));
        assertEquals(List.of("on", "off"),
            dispatcher(adminSpec()).complete(s, new String[]{"maintenance", "o"}));
    }

    @Test void argCompleterUsedOnLeaf() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("msg")
            .arg(0, (sender, args, current) -> List.of("Steve", "Alex"))
            .handler(ctx -> {})
            .build();
        FakeSender s = FakeSender.player();
        assertEquals(List.of("Steve", "Alex"), dispatcher(spec).complete(s, new String[]{""}));
        assertEquals(List.of("Alex"), dispatcher(spec).complete(s, new String[]{"Al"}));
    }

    @Test void completerExceptionYieldsEmpty() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("x")
            .arg(0, (sender, args, current) -> { throw new RuntimeException("boom"); })
            .handler(ctx -> {})
            .build();
        assertEquals(List.of(), dispatcher(spec).complete(FakeSender.player(), new String[]{""}));
    }

    // round-5: a playerOnly subcommand is hidden from console completion but visible to players
    private CommandSpec<FakeSender> playerOnlySpec() {
        return CommandSpec.<FakeSender>root("mineauth")
            .sub("reload", ctx -> {})
            .group("setspawn", sub -> sub
                .playerOnly(s -> {})
                .handler(ctx -> {}))
            .build();
    }

    @Test void playerOnlySubcommandHiddenFromConsoleCompletion() {
        FakeSender console = FakeSender.console();
        List<String> suggestions = dispatcher(playerOnlySpec()).complete(console, new String[]{""});
        assertEquals(List.of("reload"), suggestions);
        assertFalse(suggestions.contains("setspawn"));
    }

    @Test void playerOnlySubcommandVisibleToPlayerCompletion() {
        FakeSender player = FakeSender.player();
        List<String> suggestions = dispatcher(playerOnlySpec()).complete(player, new String[]{""});
        assertEquals(List.of("reload", "setspawn"), suggestions);
    }

    // Alias appears in suggestions alongside canonical name; alias-based descent works
    @Test void aliasSuggestedAndDescentResolvesViaAlias() {
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("mineauth")
            .group("maintenance", m -> m
                .aliases("maint")
                .sub("on", c -> {})
                .sub("off", c -> {}))
            .build();
        FakeSender s = FakeSender.player();
        // Both canonical name and alias should appear in root suggestions
        List<String> rootSuggestions = dispatcher(spec).complete(s, new String[]{""});
        assertTrue(rootSuggestions.contains("maintenance"));
        assertTrue(rootSuggestions.contains("maint"));
        // Partial alias prefix should surface the alias
        List<String> partial = dispatcher(spec).complete(s, new String[]{"mai"});
        assertTrue(partial.contains("maintenance"));
        assertTrue(partial.contains("maint"));
        // Descent via alias should resolve — suggests children of maintenance
        List<String> childSuggestions = dispatcher(spec).complete(s, new String[]{"maint", ""});
        assertEquals(List.of("on", "off"), childSuggestions);
    }

    // canAccess() guards permission, requirement(), and isPlayer() with try/catch->false.
    // During sibling enumeration (the loop above building the suggestion list), a throwing
    // check for just ONE sibling must only hide that one sibling — not propagate out of the
    // loop and, via the outer catch(Throwable) in complete(), blank the ENTIRE suggestion list.
    @Test void throwingHasPermissionForOneSiblingOnlyHidesThatSiblingNotTheWholeList() {
        SenderAdapter<FakeSender> flaky = new SenderAdapter<>() {
            public boolean hasPermission(FakeSender s, String perm) {
                if ("x.bad".equals(perm)) throw new RuntimeException("permission backend broken");
                return s.permissions.contains(perm);
            }
            public boolean isPlayer(FakeSender s) { return s.player; }
        };
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("cmd")
            .sub("reload", ctx -> {})
            .group("broken", b -> b.permission("x.bad").handler(ctx -> {}))
            .build();
        FakeSender s = FakeSender.player();
        List<String> suggestions = new CommandDispatcher<>(spec, flaky).complete(s, new String[]{""});
        assertEquals(List.of("reload"), suggestions);
    }

    @Test void throwingIsPlayerForOneSiblingOnlyHidesThatSiblingNotTheWholeList() {
        SenderAdapter<FakeSender> flaky = new SenderAdapter<>() {
            public boolean hasPermission(FakeSender s, String perm) { return true; }
            public boolean isPlayer(FakeSender s) { throw new RuntimeException("player-check broken"); }
        };
        CommandSpec<FakeSender> spec = CommandSpec.<FakeSender>root("cmd")
            .sub("reload", ctx -> {})
            .group("broken", b -> b.playerOnly(snd -> {}).handler(ctx -> {}))
            .build();
        FakeSender s = FakeSender.player();
        List<String> suggestions = new CommandDispatcher<>(spec, flaky).complete(s, new String[]{""});
        assertEquals(List.of("reload"), suggestions);
    }
}
