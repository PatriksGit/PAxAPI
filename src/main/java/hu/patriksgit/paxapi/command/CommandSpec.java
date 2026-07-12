package hu.patriksgit.paxapi.command;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Immutable command node. Build via {@link #root(String)}. */
public final class CommandSpec<S> {

    private final String name;
    private final List<String> aliases;
    private final String permission;                 // nullable
    private final Predicate<S> requirement;          // nullable
    private final Consumer<S> onRequirementFail;       // non-null iff requirement != null
    private final boolean playerOnly;
    private final Consumer<S> onPlayerOnlyFail;         // non-null iff playerOnly
    private final CommandHandler<S> handler;            // nullable
    private final Map<String, CommandSpec<S>> children; // unmodifiable LinkedHashMap
    private final Map<String, CommandSpec<S>> lookup;   // canonical names + aliases → spec
    private final Map<Integer, ArgumentCompleter<S>> argCompleters;
    private final Consumer<S> onDenied;                 // nullable
    private final BiConsumer<S, String> onUnknown;      // nullable
    private final BiConsumer<S, Throwable> onError;     // nullable
    private final CooldownTracker cooldownTracker;        // nullable
    private final Supplier<Duration> cooldownDuration;    // non-null iff cooldownTracker != null
    private final BiConsumer<S, Duration> onCooldown;      // non-null iff cooldownTracker != null

    private CommandSpec(Builder<S> b) {
        this.name = b.name;
        this.aliases = List.copyOf(b.aliases);
        this.permission = b.permission;
        this.requirement = b.requirement;
        this.onRequirementFail = b.onRequirementFail;
        this.playerOnly = b.playerOnly;
        this.onPlayerOnlyFail = b.onPlayerOnlyFail;
        this.handler = b.handler;
        this.children = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(b.children));
        // Build lookup: insert all canonical names first, then aliases (putIfAbsent keeps name wins)
        Map<String, CommandSpec<S>> lk = new LinkedHashMap<>();
        for (CommandSpec<S> c : this.children.values()) {
            lk.put(c.name, c);
        }
        for (CommandSpec<S> c : this.children.values()) {
            for (String a : c.aliases()) {
                lk.putIfAbsent(a, c);
            }
        }
        this.lookup = java.util.Collections.unmodifiableMap(lk);
        this.argCompleters = Map.copyOf(b.argCompleters);
        this.onDenied = b.onDenied;
        this.onUnknown = b.onUnknown;
        this.onError = b.onError;
        this.cooldownTracker = b.cooldownTracker;
        this.cooldownDuration = b.cooldownDuration;
        this.onCooldown = b.onCooldown;
    }

    public static <S> Builder<S> root(String name) { return new Builder<>(name); }

    public String name() { return name; }
    public List<String> aliases() { return aliases; }
    public String permission() { return permission; }
    public Predicate<S> requirement() { return requirement; }
    public Consumer<S> onRequirementFail() { return onRequirementFail; }
    public boolean playerOnly() { return playerOnly; }
    public Consumer<S> onPlayerOnlyFail() { return onPlayerOnlyFail; }
    public CommandHandler<S> handler() { return handler; }
    public Map<String, CommandSpec<S>> children() { return children; }
    public Map<String, CommandSpec<S>> lookup() { return lookup; }
    public Map<Integer, ArgumentCompleter<S>> argCompleters() { return argCompleters; }
    public Consumer<S> onDenied() { return onDenied; }
    public BiConsumer<S, String> onUnknown() { return onUnknown; }
    public BiConsumer<S, Throwable> onError() { return onError; }
    public CooldownTracker cooldownTracker() { return cooldownTracker; }
    public Supplier<Duration> cooldownDuration() { return cooldownDuration; }
    public BiConsumer<S, Duration> onCooldown() { return onCooldown; }

    public static final class Builder<S> {
        private final String name;
        private final List<String> aliases = new java.util.ArrayList<>();
        private String permission;
        private Predicate<S> requirement;
        private Consumer<S> onRequirementFail;
        private boolean playerOnly;
        private Consumer<S> onPlayerOnlyFail;
        private CommandHandler<S> handler;
        private final Map<String, CommandSpec<S>> children = new LinkedHashMap<>();
        private final Map<Integer, ArgumentCompleter<S>> argCompleters = new java.util.HashMap<>();
        private Consumer<S> onDenied;
        private BiConsumer<S, String> onUnknown;
        private BiConsumer<S, Throwable> onError;
        private CooldownTracker cooldownTracker;
        private Supplier<Duration> cooldownDuration;
        private BiConsumer<S, Duration> onCooldown;

        Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) throw new IllegalArgumentException("Command name must not be blank");
            this.name = name.toLowerCase(Locale.ROOT);
        }

        public Builder<S> permission(String p) { this.permission = p; return this; }
        public Builder<S> aliases(String... a) {
            for (String s : a) {
                Objects.requireNonNull(s, "alias element");
                if (s.isBlank()) throw new IllegalArgumentException("Alias must not be blank");
                aliases.add(s.toLowerCase(Locale.ROOT));
            }
            return this;
        }
        /**
         * As {@link #aliases(String...)} — convenience for aliases sourced from config as a list.
         * A distinct name (not an {@code aliases(List<String>)} overload) is deliberate: an
         * overload would make any existing unqualified {@code .aliases(null)} call ambiguous
         * between it and {@link #aliases(String...)} (both are applicable to a null argument
         * without varargs expansion) — a real source-compatibility break for a public API.
         */
        public Builder<S> aliasesFrom(List<String> a) {
            Objects.requireNonNull(a, "aliases");
            return aliases(a.toArray(new String[0]));
        }
        /**
         * Sets the requirement predicate and the callback invoked when it fails.
         *
         * <p><b>Threading:</b> on Velocity the requirement predicate is also evaluated
         * during tab-completion, which runs OFF the main thread (async). The predicate
         * must therefore be thread-safe and must not touch main-thread-only platform state.
         */
        public Builder<S> requires(Predicate<S> pred, Consumer<S> onFail) {
            this.requirement = Objects.requireNonNull(pred, "pred");
            this.onRequirementFail = Objects.requireNonNull(onFail, "onFail");
            return this;
        }
        public Builder<S> playerOnly(Consumer<S> onFail) {
            this.playerOnly = true;
            this.onPlayerOnlyFail = Objects.requireNonNull(onFail, "onFail");
            return this;
        }
        public Builder<S> handler(CommandHandler<S> h) { this.handler = h; return this; }
        public Builder<S> sub(String name, CommandHandler<S> h) {
            return group(name, b -> b.handler(h));
        }
        public Builder<S> group(String name, Consumer<Builder<S>> child) {
            Builder<S> b = new Builder<>(name);
            child.accept(b);
            if (children.containsKey(b.name))
                throw new IllegalArgumentException("Duplicate subcommand name '" + b.name + "' under '" + this.name + "'");
            // Detect alias collisions at build time so they don't silently disappear in the lookup map.
            // Intentionally NOT checked here: a new alias equal to an existing sibling's canonical
            // name. That's allowed by design — the canonical name always wins in the lookup map
            // regardless of registration order (see CommandDispatcherExecuteTest#canonicalNameWinsOverAliasCollision),
            // so the new alias is simply unreachable rather than ambiguous. Only alias-vs-alias and
            // name-vs-alias collisions are ambiguous enough to reject.
            for (CommandSpec<S> existing : children.values()) {
                for (String newAlias : b.aliases) {
                    if (existing.aliases().contains(newAlias))
                        throw new IllegalArgumentException("Alias '" + newAlias + "' on '" + b.name
                            + "' conflicts with an existing alias under '" + this.name + "'");
                }
                if (existing.aliases().contains(b.name))
                    throw new IllegalArgumentException("Subcommand name '" + b.name
                        + "' conflicts with an existing alias under '" + this.name + "'");
            }
            children.put(b.name, b.build());
            return this;
        }
        public Builder<S> arg(int index, ArgumentCompleter<S> completer) {
            argCompleters.put(index, Objects.requireNonNull(completer, "completer"));
            return this;
        }
        public Builder<S> onDenied(Consumer<S> c) { this.onDenied = c; return this; }
        public Builder<S> onUnknown(BiConsumer<S, String> c) { this.onUnknown = c; return this; }
        public Builder<S> onError(BiConsumer<S, Throwable> c) { this.onError = c; return this; }
        /**
         * Gates this node behind a per-key cooldown. {@code tracker} is caller-owned — construct
         * and hold one {@link CooldownTracker} per gated command, and call its
         * {@link CooldownTracker#evictOlderThan} on your own schedule; this library never spawns
         * threads. {@code duration} is read fresh on every check, so a config-driven cooldown
         * length can change without rebuilding this spec. {@code onCooldown} is required — it
         * receives the sender and the remaining wait whenever a check is blocked.
         *
         * <p>The cooldown is consumed by {@link CommandDispatcher#execute}, never by
         * {@link CommandDispatcher#complete} — merely tab-completing this command never counts as
         * a use. It is checked last, after permission/requirement/playerOnly all pass.
         */
        public Builder<S> cooldown(CooldownTracker tracker, Supplier<Duration> duration, BiConsumer<S, Duration> onCooldown) {
            this.cooldownTracker = Objects.requireNonNull(tracker, "tracker");
            this.cooldownDuration = Objects.requireNonNull(duration, "duration");
            this.onCooldown = Objects.requireNonNull(onCooldown, "onCooldown");
            return this;
        }

        public CommandSpec<S> build() {
            if (permission != null && permission.isBlank()) {
                throw new IllegalArgumentException(
                    "Blank permission on command '" + name + "': use null for no requirement, "
                    + "never an empty string (hasPermission(\"\") can return true for everyone).");
            }
            return new CommandSpec<>(this);
        }
    }
}
