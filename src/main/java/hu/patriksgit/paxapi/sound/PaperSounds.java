package hu.patriksgit.paxapi.sound;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;

/**
 * One-line sound playback for Paper. Sound plays at the player's own location.
 *
 * <p>Sound keys are lowercased automatically — {@code "ENTITY_PLAYER_LEVELUP"} and
 * {@code "entity.player.levelup"} are both accepted (dots and underscores work).
 */
public final class PaperSounds {

    private PaperSounds() {}

    public static void play(Player player, String soundKey) {
        play(player, soundKey, SoundCategory.MASTER, 1.0f, 1.0f);
    }

    public static void play(Player player, String soundKey, float volume, float pitch) {
        play(player, soundKey, SoundCategory.MASTER, volume, pitch);
    }

    public static void play(Player player, String soundKey, SoundCategory category, float volume, float pitch) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(soundKey, "soundKey");
        player.playSound(player.getLocation(), soundKey.toLowerCase(Locale.ROOT), category, volume, pitch);
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
        String key = soundKey.toLowerCase(Locale.ROOT);
        for (Player player : players) {
            if (player != null) player.playSound(player.getLocation(), key, category, volume, pitch);
        }
    }
}