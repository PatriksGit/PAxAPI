package hu.patriksgit.paxapi.command;

/** Platform abstraction the dispatcher needs. Implemented by each platform adapter. */
public interface SenderAdapter<S> {
    boolean hasPermission(S sender, String permission);
    boolean isPlayer(S sender);

    /**
     * Stable identity for cooldown-keying, or {@code null} if the sender has none (console,
     * command block, RCON). A {@code null} identity exempts the sender from cooldown checks
     * entirely — it is NOT bucketed together with other identity-less senders.
     *
     * <p>Default implementation returns {@code null} for every sender, so adapters written
     * before this method existed keep compiling and simply opt out of cooldown gating.
     */
    default String identity(S sender) { return null; }
}
