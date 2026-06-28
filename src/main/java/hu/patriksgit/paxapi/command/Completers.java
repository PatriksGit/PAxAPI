package hu.patriksgit.paxapi.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Platform-agnostic {@link ArgumentCompleter} helpers. Prefix filtering is applied by the dispatcher. */
public final class Completers {
    private Completers() {}

    public static <S> ArgumentCompleter<S> of(String... values) {
        List<String> list = List.of(values);
        return (sender, args, current) -> list;
    }

    public static <S> ArgumentCompleter<S> list(Supplier<? extends Collection<String>> supplier) {
        return (sender, args, current) -> new ArrayList<>(supplier.get());
    }

    public static <S> ArgumentCompleter<S> enumValues(Class<? extends Enum<?>> type) {
        List<String> names = new ArrayList<>();
        for (Enum<?> e : type.getEnumConstants()) names.add(e.name().toLowerCase(Locale.ROOT));
        return (sender, args, current) -> names;
    }
}
