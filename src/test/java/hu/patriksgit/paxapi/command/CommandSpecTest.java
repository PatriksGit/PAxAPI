package hu.patriksgit.paxapi.command;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class CommandSpecTest {

    @Test void buildsRootWithLowercaseNameAndChildrenInOrder() {
        CommandSpec<Object> spec = CommandSpec.root("MineAuth")
            .permission("mineauth.admin")
            .sub("reload", ctx -> {})
            .sub("setspawn", ctx -> {})
            .build();
        assertEquals("mineauth", spec.name());
        assertEquals("mineauth.admin", spec.permission());
        assertEquals(List.of("reload", "setspawn"), List.copyOf(spec.children().keySet()));
    }

    @Test void nestedSubtreeBuilds() {
        CommandSpec<Object> spec = CommandSpec.root("mineauth")
            .group("maintenance", m -> m.sub("on", c -> {}).sub("off", c -> {}))
            .build();
        CommandSpec<Object> maint = spec.children().get("maintenance");
        assertNotNull(maint);
        assertEquals(List.of("on", "off"), List.copyOf(maint.children().keySet()));
    }

    @Test void blankPermissionIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> CommandSpec.root("x").permission("   ").build());
    }

    @Test void nullPermissionMeansNoRequirement() {
        assertNull(CommandSpec.root("x").build().permission());
    }

    // round-5: duplicate subcommand name under the same parent is rejected
    @Test void duplicateSubcommandNameRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> CommandSpec.root("x").sub("foo", ctx -> {}).sub("foo", ctx -> {}).build());
    }

    @Test void duplicateGroupNameRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> CommandSpec.root("x")
                .group("foo", b -> b.handler(ctx -> {}))
                .group("foo", b -> b.handler(ctx -> {}))
                .build());
    }

    // round-5: name validation — null → NPE, blank → IAE
    @Test void nullNameRejected() {
        assertThrows(NullPointerException.class, () -> CommandSpec.root(null));
    }

    @Test void emptyNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.root(""));
    }

    @Test void blankNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.root("  "));
    }

    // round-5: alias validation — null element → NPE, blank → IAE
    @Test void nullAliasRejected() {
        assertThrows(NullPointerException.class,
            () -> CommandSpec.root("x").aliases((String) null));
    }

    @Test void emptyAliasRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> CommandSpec.root("x").aliases(""));
    }

    // Aliases commonly come from config as a List<String> — 4 call sites in the consuming
    // plugin repeat `.aliases(list.toArray(new String[0]))`. This helper removes that. It's a
    // distinctly-named method, not an aliases(List<String>) overload, so it can never make an
    // existing unqualified `.aliases(null)` call ambiguous (verified: an overload would).
    @Test void aliasesFromBehavesLikeVarargs() {
        CommandSpec<Object> spec = CommandSpec.root("x").aliasesFrom(List.of("a", "b")).build();
        assertEquals(List.of("a", "b"), spec.aliases());
    }

    @Test void aliasesFromValidatesElementsSameAsVarargs() {
        assertThrows(IllegalArgumentException.class,
            () -> CommandSpec.root("x").aliasesFrom(List.of("")));
    }

    @Test void aliasesFromNullListRejected() {
        assertThrows(NullPointerException.class,
            () -> CommandSpec.root("x").aliasesFrom(null));
    }

    // FIX 5: CommandContext.args() returns a defensive copy
    @Test void commandContextArgsReturnsCopy() {
        String[] original = {"a", "b"};
        CommandContext<Object> ctx = new CommandContext<>(new Object(), "cmd", List.of(), original);
        String[] copy1 = ctx.args();
        String[] copy2 = ctx.args();
        assertNotSame(original, copy1);
        assertNotSame(copy1, copy2);
        assertEquals("a", ctx.arg(0));
        assertEquals("b", ctx.arg(1));
        assertEquals(2, ctx.argCount());
        // Mutating the returned array does not affect the context
        copy1[0] = "MUTATED";
        assertEquals("a", ctx.arg(0));
    }

    // round-5: CommandContext has value semantics over its args content
    @Test void commandContextEqualsAndHashCodeByValue() {
        Object sender = new Object();
        // distinct arrays with equal content must be treated as equal
        CommandContext<Object> a = new CommandContext<>(sender, "cmd", List.of("p"), new String[]{"a", "b"});
        CommandContext<Object> b = new CommandContext<>(sender, "cmd", List.of("p"), new String[]{"a", "b"});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test void commandContextNotEqualWhenArgsDiffer() {
        Object sender = new Object();
        CommandContext<Object> a = new CommandContext<>(sender, "cmd", List.of("p"), new String[]{"a", "b"});
        CommandContext<Object> c = new CommandContext<>(sender, "cmd", List.of("p"), new String[]{"a", "x"});
        assertNotEquals(a, c);
    }

    @Test void commandContextToStringShowsArgsContents() {
        CommandContext<Object> ctx = new CommandContext<>(new Object(), "cmd", List.of(), new String[]{"a", "b"});
        String s = ctx.toString();
        // must render the array contents, not an array identity/address like [Ljava.lang.String;@...
        assertTrue(s.contains("[a, b]"), "toString must show args contents; got: " + s);
        assertFalse(s.contains("[Ljava.lang.String"), "toString must not show array address; got: " + s);
    }

    @Test void cooldownRequiresNonNullTracker() {
        assertThrows(NullPointerException.class,
            () -> CommandSpec.root("x").cooldown(null, () -> Duration.ofSeconds(1), (s, r) -> {}));
    }

    @Test void cooldownRequiresNonNullDuration() {
        assertThrows(NullPointerException.class,
            () -> CommandSpec.root("x").cooldown(new CooldownTracker(), null, (s, r) -> {}));
    }

    @Test void cooldownRequiresNonNullOnCooldown() {
        assertThrows(NullPointerException.class,
            () -> CommandSpec.root("x").cooldown(new CooldownTracker(), () -> Duration.ofSeconds(1), null));
    }

    @Test void cooldownFieldsAccessibleAfterBuild() {
        CooldownTracker tracker = new CooldownTracker();
        Supplier<Duration> duration = () -> Duration.ofSeconds(5);
        BiConsumer<Object, Duration> onCooldown = (s, r) -> {};
        CommandSpec<Object> spec = CommandSpec.root("x").cooldown(tracker, duration, onCooldown).build();
        assertSame(tracker, spec.cooldownTracker());
        assertSame(duration, spec.cooldownDuration());
        assertSame(onCooldown, spec.onCooldown());
    }

    @Test void nullCooldownFieldsWhenNotConfigured() {
        CommandSpec<Object> spec = CommandSpec.root("x").build();
        assertNull(spec.cooldownTracker());
        assertNull(spec.cooldownDuration());
        assertNull(spec.onCooldown());
    }
}
