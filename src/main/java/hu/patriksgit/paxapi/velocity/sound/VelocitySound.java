ackage hu.patriksgit.paxapi.velocity.sound;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

public final class VelocitySound {

    private VelocitySound() {}

    public static void play(Player player, String soundKey) {
        play(player, soundKey, Sound.Source.MASTER, 1.0f, 1.0f);
    }

    public static void play(Player player, String soundKey, float volume, float pitch) {
        play(player, soundKey, Sound.Source.MASTER, volume, pitch);
    }

    public static void play(Player player, String soundKey, Sound.Source source, float volume, float pitch) {
        player.playSound(
                Sound.sound(Key.key(soundKey), source, volume, pitch),
                Sound.Emitter.self()
        );
    }

    public static void playAll(Iterable<Player> players, String soundKey) {
        Sound sound = Sound.sound(Key.key(soundKey), Sound.Source.MASTER, 1.0f, 1.0f);
        for (Player player : players) {
            player.playSound(sound, Sound.Emitter.self());
        }
    }

    public static void playAll(Iterable<Player> players, String soundKey, float volume, float pitch) {
        Sound sound = Sound.sound(Key.key(soundKey), Sound.Source.MASTER, volume, pitch);
        for (Player player : players) {
            player.playSound(sound, Sound.Emitter.self());
        }
    }
}
