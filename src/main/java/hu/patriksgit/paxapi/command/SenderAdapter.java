package hu.patriksgit.paxapi.command;

/** Platform abstraction the dispatcher needs. Implemented by each platform adapter. */
public interface SenderAdapter<S> {
    boolean hasPermission(S sender, String permission);
    boolean isPlayer(S sender);
}
