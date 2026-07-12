package hu.patriksgit.paxapi.command.paper;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperSenderAdapterTest {

    @Test void identityReturnsPlayerUuidAsString() {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        assertEquals(id.toString(), PaperSenderAdapter.INSTANCE.identity(p));
    }

    @Test void identityReturnsNullForNonPlayerSender() {
        CommandSender sender = mock(CommandSender.class);
        assertNull(PaperSenderAdapter.INSTANCE.identity(sender));
    }
}
