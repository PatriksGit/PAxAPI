package hu.patriksgit.paxapi.sound;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

import java.util.Locale;
import java.util.Objects;

/**
 * One-line sound playback for Velocity. Always uses {@link Sound.Emitter#self()}
 * so the sound plays at the player's own position (required on Velocity 3.3+).
 *
 * <p>Sound keys are lowercased automatically — {@code "ENTITY.PLAYER.LEVELUP"} and
 * {@code "entity.player.levelup"} are treated identically.
 */
public final class VelocitySounds {

    private VelocitySounds() {}

    public static void play(Player player, String soundKey) {
        play(player, soundKey, Sound.Source.MASTER, 1.0f, 1.0f);
    }

    public static void play(Player player, String soundKey, float volume, float pitch) {
        play(player, soundKey, Sound.Source.MASTER, volume, pitch);
    }

    public static void play(Player player, String soundKey, Sound.Source source, float volume, float pitch) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(soundKey, "soundKey");
        Key key = Key.key(soundKey.toLowerCase(Locale.ROOT));
        player.playSound(Sound.sound(key, source, volume, pitch), Sound.Emitter.self());
    }

    public static void playAll(Iterable<Player> players, String soundKey) {
        playAll(players, soundKey, Sound.Source.MASTER, 1.0f, 1.0f);
    }

    public static void playAll(Iterable<Player> players, String soundKey, float volume, float pitch) {
        playAll(players, soundKey, Sound.Source.MASTER, volume, pitch);
    }

    public static void playAll(Iterable<Player> players, String soundKey, Sound.Source source, float volume, float pitch) {
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(soundKey, "soundKey");
        Sound sound = Sound.sound(Key.key(soundKey.toLowerCase(Locale.ROOT)), source, volume, pitch);
        for (Player player : players) {
            if (player != null) player.playSound(sound, Sound.Emitter.self());
        }
    }
}