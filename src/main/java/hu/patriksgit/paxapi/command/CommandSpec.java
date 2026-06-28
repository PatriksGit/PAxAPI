ackage hu.patriksgit.paxapi.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
