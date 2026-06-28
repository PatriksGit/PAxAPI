package hu.patriksgit.paxapi.command;

@FunctionalInterface
public interface CommandHandler<S> {
    void handle(CommandContext<S> ctx);
}
