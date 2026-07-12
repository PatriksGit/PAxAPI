package hu.patriksgit.paxapi.command.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VelocitySenderAdapterTest {

    @Test void identityReturnsPlayerUuidAsString() {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        assertEquals(id.toString(), VelocitySenderAdapter.INSTANCE.identity(p));
    }

    @Test void identityReturnsNullForNonPlayerSender() {
        CommandSource sender = mock(CommandSource.class);
        assertNull(VelocitySenderAdapter.INSTANCE.identity(sender));
    }
}
