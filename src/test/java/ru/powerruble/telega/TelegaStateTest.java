package ru.powerruble.telega;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

final class TelegaStateTest {
    @Test
    void completeLinkStoresChatAndPlayerMapping() {
        TelegaState state = new TelegaState();
        UUID playerId = UUID.randomUUID();
        state.createPendingLink(playerId, "Steve", "abc123", Instant.EPOCH.plusSeconds(60));

        TelegaState.CompleteLinkResult result = state.completeLink(" ABC123 ", 1001L, "steve_tg", Instant.EPOCH.plusSeconds(1));

        assertTrue(result.success());
        assertEquals(playerId, result.link().playerId());
        assertEquals("Steve", state.linkByChat(1001L).playerName());
        assertEquals(1001L, state.linkByPlayer(playerId).chatId());
        assertFalse(state.hasPendingCode("ABC123"));
    }

    @Test
    void expiredLinkCodeFailsAndGetsRemoved() {
        TelegaState state = new TelegaState();
        state.createPendingLink(UUID.randomUUID(), "Alex", "CODE", Instant.EPOCH.plusSeconds(5));

        TelegaState.CompleteLinkResult result = state.completeLink("CODE", 1002L, "alex_tg", Instant.EPOCH.plusSeconds(6));

        assertFalse(result.success());
        assertNull(state.linkByChat(1002L));
        assertFalse(state.hasPendingCode("CODE"));
    }

    @Test
    void nbtRoundTripPreservesLinksAndPendingCodes() {
        TelegaState state = new TelegaState();
        UUID linkedPlayer = UUID.randomUUID();
        UUID pendingPlayer = UUID.randomUUID();
        state.createPendingLink(linkedPlayer, "Steve", "LINKME", Instant.EPOCH.plusSeconds(60));
        state.completeLink("LINKME", 1003L, "steve_tg", Instant.EPOCH.plusSeconds(1));
        state.createPendingLink(pendingPlayer, "Alex", "WAITING", Instant.EPOCH.plusSeconds(120));

        NbtCompound nbt = state.writeNbt(new NbtCompound());
        TelegaState loaded = TelegaState.fromNbt(nbt);

        assertNotNull(loaded.linkByPlayer(linkedPlayer));
        assertEquals("Steve", loaded.linkByChat(1003L).playerName());
        assertTrue(loaded.hasPendingCode("WAITING"));
    }
}
