package hu.patriksgit.paxapi.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Routes execution and tab-completion through a {@link CommandSpec} tree. */
public final class CommandDispatcher<S> {

    private final CommandSpec<S> root;
    private final SenderAdapter<S> adapter;
    private final org.slf4j.Logger logger;

    public CommandDispatcher(CommandSpec<S> root, SenderAdapter<S> adapter) {
        this(root, adapter, null);
    }

    public CommandDispatcher(CommandSpec<S> root, SenderAdapter<S> adapter, org.slf4j.Logger logger) {
        this.root = root;
        this.adapter = adapter;
        this.logger = logger;
    }

    public CommandSpec<S> root() { return root; }

    public void execute(S sender, String label, String[] argsIn) {
        String[] args = argsIn == null ? new String[0] : argsIn;
        CommandSpec<S> node = root;
        List<String> path = new ArrayList<>();
        Consumer<S> effDenied = null;
        BiConsumer<S, String> effUnknown = null;
        BiConsumer<S, Throwable> effError = null;
        int i = 0;

        while (true) {
            if (node.onDenied() != null) effDenied = node.onDenied();
            if (node.onUnknown() != null) effUnknown = node.onUnknown();
            if (node.onError() != null) effError = node.onError();

            final CommandSpec<S> n = node;
            if (n.permission() != null && !gate(() -> adapter.hasPermission(sender, n.permission()), effDenied, sender, effError)) return;
            if (n.requirement() != null && !gate(() -> n.requirement().test(sender), n.onRequirementFail(), sender, effError)) return;
            if (n.playerOnly() && !gate(() -> adapter.isPlayer(sender), n.onPlayerOnlyFail(), sender, effError)) return;

            if (i < args.length) {
                String token = args[i].toLowerCase(Locale.ROOT);
                CommandSpec<S> child = node.lookup().get(token);
                if (child != null) { path.add(token); node = child; i++; continue; }
                // No child matched — fall back to this node's handler (supports mixed handler+children nodes)
                if (node.handler() != null) {
                    runHandler(node, sender, label, path, slice(args, i), effError);
                    return;
                }
                final BiConsumer<S, String> eu = effUnknown;
                final String tok = args[i];
                if (eu != null) guard(() -> eu.accept(sender, tok), effError, sender);
                return;
            } else {
                if (node.handler() != null) {
                    runHandler(node, sender, label, path, new String[0], effError);
                    return;
                }
                final BiConsumer<S, String> eu = effUnknown;
                if (eu != null) guard(() -> eu.accept(sender, ""), effError, sender);
                return;
            }
        }
    }

    /**
     * Shared shape behind the permission/requirement/playerOnly gates in {@link #execute}: run
     * {@code check}; if it throws, route to onError (else swallow) and report "blocked". If it
     * returns {@code false}, invoke {@code onFail} (guarded the same way) and report "blocked".
     * Do NOT reuse this for handler execution — {@link #runHandler} is intentionally asymmetric
     * (a handler exception with no onError PROPAGATES rather than being swallowed here).
     */
    private boolean gate(BooleanSupplier check, Consumer<S> onFail, S sender, BiConsumer<S, Throwable> effError) {
        boolean ok;
        try {
            ok = check.getAsBoolean();
        } catch (Throwable t) {
            guardThrowable(effError, sender, t);
            return false;
        }
        if (!ok) {
            if (onFail != null) guard(() -> onFail.accept(sender), effError, sender);
            return false;
        }
        return true;
    }

    /** Guards an infrastructure call: routes any thrown Throwable to onError (if set), else swallows it. */
    private void guard(Runnable action, BiConsumer<S, Throwable> onError, S sender) {
        try {
            action.run();
        } catch (Throwable t) {
            if (onError != null) { try { onError.accept(sender, t); } catch (Throwable ignored) {} }
        }
    }

    /** Routes an already-caught Throwable to onError (if set), else swallows it. */
    private void guardThrowable(BiConsumer<S, Throwable> onError, S sender, Throwable t) {
        if (onError != null) { try { onError.accept(sender, t); } catch (Throwable ignored) {} }
    }

    private void runHandler(CommandSpec<S> node, S sender, String label, List<String> path,
                            String[] remaining, BiConsumer<S, Throwable> effError) {
        try {
            node.handler().handle(new CommandContext<>(sender, label, List.copyOf(path), remaining));
        } catch (Throwable t) {
            if (effError != null) {
                // The configured onError callback is caller-supplied and must not itself be
                // able to crash execute() uncaught — every other callback invocation in this
                // class is guarded (see guard()/guardThrowable()); this one was the exception.
                try { effError.accept(sender, t); } catch (Throwable ignored) {}
            } else {
                throw new RuntimeException("Command handler '" + String.join(" ", path) + "' failed", t);
            }
        }
    }

    public List<String> complete(S sender, String[] argsIn) {
        try {
            String[] args = argsIn == null ? new String[0] : argsIn;
            if (!canAccess(root, sender)) return List.of();
            CommandSpec<S> node = root;
            int i = 0;
            while (i < args.length - 1) {
                CommandSpec<S> child = node.lookup().get(args[i].toLowerCase(Locale.ROOT));
                if (child == null) break;
                if (!canAccess(child, sender)) return List.of();
                node = child;
                i++;
            }
            String current = args.length == 0 ? "" : args[args.length - 1];
            String lc = current.toLowerCase(Locale.ROOT);
            int relIndex = args.length == 0 ? 0 : (args.length - 1 - i);

            if (!node.children().isEmpty() && relIndex == 0) {
                List<String> out = new ArrayList<>();
                // Suggest child names AND aliases (lookup contains both)
                for (Map.Entry<String, CommandSpec<S>> entry : node.lookup().entrySet()) {
                    if (entry.getKey().startsWith(lc) && canAccess(entry.getValue(), sender))
                        out.add(entry.getKey());
                }
                // Also include arg[0] completers for mixed handler+children nodes
                ArgumentCompleter<S> ac0 = node.argCompleters().get(0);
                if (ac0 != null) {
                    List<String> extra = ac0.complete(sender, slice(args, i), current);
                    if (extra != null) {
                        for (String s : extra) {
                            if (s.toLowerCase(Locale.ROOT).startsWith(lc) && !out.contains(s)) out.add(s);
                        }
                    }
                }
                return out;
            }
            ArgumentCompleter<S> ac = node.argCompleters().get(relIndex);
            if (ac == null) return List.of();
            List<String> raw = ac.complete(sender, slice(args, i), current);
            if (raw == null || raw.isEmpty()) return List.of();
            List<String> out = new ArrayList<>();
            for (String suggestion : raw) {
                if (suggestion.toLowerCase(Locale.ROOT).startsWith(lc)) out.add(suggestion);
            }
            return out;
        } catch (Throwable e) {
            if (logger != null) logger.warn("Tab completion failed for '{}': {}", root.name(), e.getMessage());
            return List.of();
        }
    }

    // Guards the WHOLE body, not just requirement() — canAccess() is called once per sibling
    // during tab-completion's suggestion-list enumeration (see complete() above). Without a
    // guard around hasPermission/isPlayer too, a throwing check for just ONE sibling propagates
    // out of that loop and, via complete()'s outer catch(Throwable), blanks the ENTIRE
    // suggestion list instead of just hiding the one offending node.
    private boolean canAccess(CommandSpec<S> node, S sender) {
        try {
            if (node.permission() != null && !adapter.hasPermission(sender, node.permission())) return false;
            if (node.requirement() != null && !node.requirement().test(sender)) return false;
            if (node.playerOnly() && !adapter.isPlayer(sender)) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static String[] slice(String[] arr, int from) {
        if (from <= 0) return arr.clone();
        if (from >= arr.length) return new String[0];
        String[] r = new String[arr.length - from];
        System.arraycopy(arr, from, r, 0, r.length);
        return r;
    }
}
