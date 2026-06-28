package hu.patriksgit.paxapi.command;

import java.util.List;

@FunctionalInterface
public interface ArgumentCompleter<S> {
    /** @param args args relative to the owning node; @param current the token being typed */
    List<String> complete(S sender, String[] args, String current);
}
