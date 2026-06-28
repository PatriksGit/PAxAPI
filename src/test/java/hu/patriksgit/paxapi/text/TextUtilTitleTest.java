package hu.patriksgit.paxapi.text;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link TextUtil#sendTitle} and the private {@code parseTimes} timing
 * fallback (round-3 fix: negative / malformed timing → {@code null} = Minecraft default).
 */
class TextUtilTitleTest {

    @AfterEach
    void resetExpander() {
        TextUtil.setExpander(null);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Audience that records the last Title passed to {@code showTitle}. */
    private static final class TitleAudience implements Audience {
        final List<Title> titles = new ArrayList<>();
        @Override public void showTitle(Title title) { titles.add(title); }
    }

    @Test
    void emptyListIsNoOp() {
        TitleAudience a = new TitleAudience();
        TextUtil.sendTitle(a, List.of());
        assertEquals(0, a.titles.size());
    }

    @Test
    void nullListIsNoOp() {
        TitleAudience a = new TitleAudience();
        TextUtil.sendTitle(a, null);
        assertEquals(0, a.titles.size());
    }

    @Test
    void titleOnlyLineUsesEmptySubtitleAndDefaultTiming() {
        TitleAudience a = new TitleAudience();
        TextUtil.sendTitle(a, List.of("&aHello"));
        assertEquals(1, a.titles.size());
        Title t = a.titles.get(0);
        assertEquals("Hello", plain(t.title()));
        assertEquals("", plain(t.subtitle()));
        // Only one line → no timing spec → default timing (null Times)
        assertNull(t.times());
    }

    @Test
    void twoLinesMapToTitleAndSubtitle() {
        TitleAudience a = new TitleAudience();
        TextUtil.sendTitle(a, List.of("&aTitle", "&7Subtitle"));
        Title t = a.titles.get(0);
        assertEquals("Title", plain(t.title()));
        assertEquals("Subtitle", plain(t.subtitle()));
        // Only two lines → no timing spec → default timing (null Times)
        assertNull(t.times());
    }

    @Test
    void validTimingSpecIsApplied() {
        TitleAudience a = new TitleAudience();
        TextUtil.sendTitle(a, List.of("Title", "Sub", "10,60,20"));
        Title t = a.titles.get(0);
        assertNotNull(t.times());
        assertEquals(Duration.ofMillis(10 * 50L), t.times().fadeIn());
        assertEquals(Duration.ofMillis(60 * 50L), t.times().stay());
        assertEquals(Duration.ofMillis(20 * 50L), t.times().fadeOut());
    }

    @Test
    void negativeTimingFallsBackToDefault() {
        // round-3 fix: a negative tick value makes parseTimes return null → default timing
        TitleAudience a = new TitleAudience();
        TextUtil.sendTitle(a, List.of("Title", "Sub", "-1,60,20"));
        assertNull(a.titles.get(0).times(),
                "Negative timing must fall back to default (null Times)");
    }

    @Test
    void malformedTimingFallsBackToDefault() {
        // Non-integer / malformed timing string → default timing
        TitleAudience a = new TitleAudience();
        TextUtil.sendTitle(a, List.of("Title", "Sub", "abc"));
        assertNull(a.titles.get(0).times(),
                "Malformed timing must fall back to default (null Times)");
    }

    @Test
    void tooFewTimingPartsFallsBackToDefault() {
        // "10,60" has only two parts → parseTimes returns null → default timing
        TitleAudience a = new TitleAudience();
        TextUtil.sendTitle(a, List.of("Title", "Sub", "10,60"));
        assertNull(a.titles.get(0).times());
    }

    @Test
    void titleAppliesPlaceholders() {
        TitleAudience a = new TitleAudience();
        TextUtil.sendTitle(a, List.of("Hi %name%", "Bye %name%"),
                java.util.Map.of("name", "Steve"));
        Title t = a.titles.get(0);
        assertEquals("Hi Steve", plain(t.title()));
        assertEquals("Bye Steve", plain(t.subtitle()));
    }
}
