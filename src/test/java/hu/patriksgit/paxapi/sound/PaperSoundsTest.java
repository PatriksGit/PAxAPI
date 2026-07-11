package hu.patriksgit.paxapi.sound;

import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperSoundsTest {

    private Player mockPlayer() {
        Player p = mock(Player.class);
        when(p.getLocation()).thenReturn(mock(Location.class));
        return p;
    }

    @Test void playWithValidKeyStillCallsPlayerPlaySound() {
        Player p = mockPlayer();
        PaperSounds.play(p, "entity.player.levelup", SoundCategory.MASTER, 1.0f, 1.0f);
        verify(p).playSound(any(Location.class), eq("entity.player.levelup"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
    }

    // Bukkit's playSound throws ResourceLocationException (RuntimeException) for a malformed
    // key deep inside CraftBukkit — must not propagate out of play(), and must not attempt the
    // Bukkit call at all for a key that can never be valid.
    @Test void playWithMalformedKeyDoesNotThrowAndIsNoOp() {
        Player p = mockPlayer();
        assertDoesNotThrow(() -> PaperSounds.play(p, "not a valid key!!", SoundCategory.MASTER, 1.0f, 1.0f));
        verify(p, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
    }

    // playAll must validate once before the loop (like VelocitySounds already does) so a bad
    // key doesn't abort mid-batch and leave later players with no sound attempt at all.
    @Test void playAllWithMalformedKeySkipsEveryPlayerCleanlyWithoutPartialPlayback() {
        Player p1 = mockPlayer();
        Player p2 = mockPlayer();
        assertDoesNotThrow(() -> PaperSounds.playAll(List.of(p1, p2), "not a valid key!!", SoundCategory.MASTER, 1.0f, 1.0f));
        verify(p1, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
        verify(p2, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
    }

    @Test void playAllWithValidKeyStillPlaysForEveryPlayer() {
        Player p1 = mockPlayer();
        Player p2 = mockPlayer();
        PaperSounds.playAll(List.of(p1, p2), "entity.player.levelup", SoundCategory.MASTER, 1.0f, 1.0f);
        verify(p1).playSound(any(Location.class), eq("entity.player.levelup"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
        verify(p2).playSound(any(Location.class), eq("entity.player.levelup"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
    }

    // Pins the corrected class Javadoc contract: underscores are legal key characters (so this
    // passes validation, unlike playWithMalformedKeyDoesNotThrowAndIsNoOp's case) but are NOT
    // translated to dots — the literal underscore string is forwarded unchanged, which resolves
    // to a different (likely nonexistent) resource path than the dotted form.
    @Test void underscoreKeyIsForwardedLiterallyNotTranslatedToDots() {
        Player p = mockPlayer();
        PaperSounds.play(p, "ENTITY_PLAYER_LEVELUP", SoundCategory.MASTER, 1.0f, 1.0f);
        verify(p).playSound(any(Location.class), eq("entity_player_levelup"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
    }
}
