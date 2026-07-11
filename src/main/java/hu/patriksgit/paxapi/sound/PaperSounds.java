package hu.patriksgit.paxapi.sound;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;

/**
 * One-line sound playback for Paper. Sound plays at the player's own location.
 *
 * <p>Sound keys are lowercased automatically — {@code "ENTITY.PLAYER.LEVELUP"} and
 * {@code "entity.player.levelup"} are treated identically. Underscores are NOT translated to
 * dots: a legacy Bukkit-enum-style key like {@code "ENTITY_PLAYER_LEVELUP"} passes key
 * validation (underscores are legal key characters) but resolves to a different, nonexistent
 * resource path, so it silently plays no sound — pass the dotted form.
 *
 * <p>A malformed key is a silent no-op rather than an exception — matches
 * {@code VelocitySounds}, so the same config value behaves identically on both platforms.
 * Without this pre-check, Bukkit's own key parser throws a {@code RuntimeException} deep
 * inside {@code Player.playSound}, and {@code playAll} would abort mid-batch on the first
 * bad key, leaving every later player with no sound attempt at all.
 */
public final class PaperSounds {

    private PaperSounds() {}

    /** Lowercases and validates a sound key; returns {@code null} on a malformed key (caller skips). */
    private static String safeKey(String soundKey) {
        String lower = soundKey.toLowerCase(Locale.ROOT);
        try {
            Key.key(lower);
            return lower;
        } catch (InvalidKeyException e) {
            return null;
        }
    }

    public static void play(Player player, String soundKey) {
        play(player, soundKey, SoundCategory.MASTER, 1.0f, 1.0f);
    }

    public static void play(Player player, String soundKey, float volume, float pitch) {
        play(player, soundKey, SoundCategory.MASTER, volume, pitch);
    }

    public static void play(Player player, String soundKey, SoundCategory category, float volume, float pitch) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(soundKey, "soundKey");
        String key = safeKey(soundKey);
        if (key == null) return;
        player.playSound(player.getLocation(), key, category, volume, pitch);
    }

    public static void playAll(Iterable<Player> players, String soundKey) {
        playAll(players, soundKey, SoundCategory.MASTER, 1.0f, 1.0f);
    }

    public static void playAll(Iterable<Player> players, String soundKey, float volume, float pitch) {
        playAll(players, soundKey, SoundCategory.MASTER, volume, pitch);
    }

    public static void playAll(Iterable<Player> players, String soundKey, SoundCategory category, float volume, float pitch) {
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(soundKey, "soundKey");
        String key = safeKey(soundKey);
        if (key == null) return;
        for (Player player : players) {
            if (player != null) player.playSound(player.getLocation(), key, category, volume, pitch);
        }
    }
}