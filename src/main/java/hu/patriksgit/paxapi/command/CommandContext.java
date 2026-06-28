ackage hu.patriksgit.paxapi.command;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** What a handler receives. {@code path} = matched subcommand tokens; {@code args} = remaining args after the path. */
public record CommandContext<S>(S sender, String label, List<String> path, String[] args) {
    @Override public String[] args() { return args.clone(); }
    public String arg(int i) { return i >= 0 && i < args.length ? args[i] : null; }
    public int argCount() { return args.length; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CommandContext<?> other)) return false;
        return Objects.equals(sender, other.sender)
            && Objects.equals(label, other.label)
            && Objects.equals(path, other.path)
            && Arrays.equals(args, other.args);
    }

    @Override public int hashCode() {
        return 31 * Objects.hash(sender, label, path) + Arrays.hashCode(args);
    }

    @Override public String toString() {
        return "CommandContext[sender=" + sender
            + ", label=" + label
            + ", path=" + path
            + ", args=" + Arrays.toString(args) + "]";
    }
}
