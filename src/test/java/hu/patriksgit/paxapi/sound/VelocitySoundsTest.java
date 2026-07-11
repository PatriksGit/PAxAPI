package hu.patriksgit.paxapi.sound;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * VelocitySounds had zero test coverage before this — the class-level Javadoc's "malformed key
 * is a silent no-op, matching PaperSounds" contract, playAll's validate-once-before-the-loop
 * behavior, and null-player skipping were all asserted only in prose, never in code.
 */
class VelocitySoundsTest {

    private Player mockPlayer() {
        return mock(Player.class);
    }

    @Test void playWithValidKeyCallsPlayerPlaySound() {
        Player p = mockPlayer();
        VelocitySounds.play(p, "entity.player.levelup", Sound.Source.MASTER, 1.0f, 1.0f);

        ArgumentCaptor<Sound> captor = ArgumentCaptor.forClass(Sound.class);
        verify(p).playSound(captor.capture(), eq(Sound.Emitter.self()));
        Sound sound = captor.getValue();
        assertEquals(Key.key("entity.player.levelup"), sound.name());
        assertEquals(Sound.Source.MASTER, sound.source());
        assertEquals(1.0f, sound.volume());
        assertEquals(1.0f, sound.pitch());
    }

    @Test void caseIsNormalizedBeforeKeyParsing() {
        Player p = mockPlayer();
        VelocitySounds.play(p, "ENTITY.PLAYER.LEVELUP", Sound.Source.MASTER, 1.0f, 1.0f);

        ArgumentCaptor<Sound> captor = ArgumentCaptor.forClass(Sound.class);
        verify(p).playSound(captor.capture(), eq(Sound.Emitter.self()));
        assertEquals(Key.key("entity.player.levelup"), captor.getValue().name());
    }

    // Matches the class's own documented contract: a malformed key is a silent no-op, not an
    // InvalidKeyException — unlike calling Key.key(...) directly, which would throw.
    @Test void playWithMalformedKeyDoesNotThrowAndIsNoOp() {
        Player p = mockPlayer();
        assertDoesNotThrow(() -> VelocitySounds.play(p, "not a valid key!!", Sound.Source.MASTER, 1.0f, 1.0f));
        verify(p, never()).playSound(any(Sound.class), any(Sound.Emitter.class));
    }

    // playAll must validate once before the loop so a bad key doesn't leave later players with
    // no sound attempt at all (mirrors PaperSounds' equivalent fix/test).
    @Test void playAllWithMalformedKeySkipsEveryPlayerCleanlyWithoutPartialPlayback() {
        Player p1 = mockPlayer();
        Player p2 = mockPlayer();
        assertDoesNotThrow(() -> VelocitySounds.playAll(List.of(p1, p2), "not a valid key!!", Sound.Source.MASTER, 1.0f, 1.0f));
        verify(p1, never()).playSound(any(Sound.class), any(Sound.Emitter.class));
        verify(p2, never()).playSound(any(Sound.class), any(Sound.Emitter.class));
    }

    @Test void playAllWithValidKeyStillPlaysForEveryPlayer() {
        Player p1 = mockPlayer();
        Player p2 = mockPlayer();
        VelocitySounds.playAll(List.of(p1, p2), "entity.player.levelup", Sound.Source.MASTER, 1.0f, 1.0f);
        verify(p1).playSound(any(Sound.class), eq(Sound.Emitter.self()));
        verify(p2).playSound(any(Sound.class), eq(Sound.Emitter.self()));
    }

    @Test void playAllSkipsNullPlayersInTheIterable() {
        Player p1 = mockPlayer();
        assertDoesNotThrow(() -> VelocitySounds.playAll(
                Arrays.asList(p1, null), "entity.player.levelup", Sound.Source.MASTER, 1.0f, 1.0f));
        verify(p1).playSound(any(Sound.class), eq(Sound.Emitter.self()));
    }
}
