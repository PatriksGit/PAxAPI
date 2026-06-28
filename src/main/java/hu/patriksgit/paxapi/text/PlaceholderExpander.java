package hu.patriksgit.paxapi.text;

/**
 * Soft-dependency bridge for external placeholder systems (PlaceholderAPI on Paper,
 * MiniPlaceholders on Velocity). Register once via {@link TextUtil#setExpander(PlaceholderExpander)}
 * in onEnable; TextUtil's {@code send*} methods will call it automatically before
 * resolving internal {@code %key%} placeholders.
 *
 * <p>The {@code player} argument is the audience context — cast to the
 * platform-specific player type inside your implementation.
 */
@FunctionalInterface
public interface PlaceholderExpander {
    /**
     * Expands external placeholders in {@code text} for the given {@code player} context.
     *
     * @param text   the raw text that may contain external placeholders
     * @param player platform-specific player (e.g. {@code Player} on Paper/Velocity); may be null
     * @return text with external placeholders replaced
     */
    String expand(String text, Object player);
}
