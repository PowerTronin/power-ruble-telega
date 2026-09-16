package ru.powerruble.telega;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

public final class TelegaState extends PersistentState {
    private static final String STATE_KEY = PowerRubleTelegaMod.MOD_ID;
    private static final String LINKS_KEY = "links";
    private static final String PENDING_KEY = "pendingLinks";
    private static final String PLAYER_ID_KEY = "playerId";
    private static final String PLAYER_NAME_KEY = "playerName";
    private static final String CHAT_ID_KEY = "chatId";
    private static final String TELEGRAM_USERNAME_KEY = "telegramUsername";
    private static final String LINKED_AT_KEY = "linkedAt";
    private static final String CODE_KEY = "code";
    private static final String EXPIRES_AT_KEY = "expiresAt";

    private final Map<UUID, LinkRecord> linksByPlayer = new HashMap<>();
    private final Map<Long, LinkRecord> linksByChat = new HashMap<>();
    private final Map<String, PendingLink> pendingByCode = new HashMap<>();

    public static TelegaState get(MinecraftServer server) {
        PersistentStateManager manager = server.getOverworld().getPersistentStateManager();
        return manager.getOrCreate(TelegaState::fromNbt, TelegaState::new, STATE_KEY);
    }

    public static TelegaState fromNbt(NbtCompound nbt) {
        TelegaState state = new TelegaState();

        if (nbt.contains(LINKS_KEY, NbtElement.LIST_TYPE)) {
            NbtList links = nbt.getList(LINKS_KEY, NbtElement.COMPOUND_TYPE);
            for (int index = 0; index < links.size(); index++) {
                LinkRecord link = LinkRecord.fromNbt(links.getCompound(index));
                if (link != null) {
                    state.putLink(link);
                }
            }
        }

        if (nbt.contains(PENDING_KEY, NbtElement.LIST_TYPE)) {
            NbtList pendingLinks = nbt.getList(PENDING_KEY, NbtElement.COMPOUND_TYPE);
            for (int index = 0; index < pendingLinks.size(); index++) {
                PendingLink pending = PendingLink.fromNbt(pendingLinks.getCompound(index));
                if (pending != null) {
                    state.pendingByCode.put(normalizeCode(pending.code()), pending);
                }
            }
        }

        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList links = new NbtList();
        linksByPlayer.values().stream()
            .sorted(Comparator.comparing(LinkRecord::playerName, String.CASE_INSENSITIVE_ORDER))
            .forEach(link -> links.add(link.toNbt()));
        nbt.put(LINKS_KEY, links);

        NbtList pendingLinks = new NbtList();
        pendingByCode.values().stream()
            .sorted(Comparator.comparing(PendingLink::code))
            .forEach(pending -> pendingLinks.add(pending.toNbt()));
        nbt.put(PENDING_KEY, pendingLinks);
        return nbt;
    }

    public LinkRecord linkByPlayer(UUID playerId) {
        return linksByPlayer.get(playerId);
    }

    public LinkRecord linkByChat(long chatId) {
        return linksByChat.get(chatId);
    }

    public boolean hasPendingCode(String code) {
        return pendingByCode.containsKey(normalizeCode(code));
    }

    public void createPendingLink(UUID playerId, String playerName, String code, Instant expiresAt) {
        removePendingByPlayer(playerId);
        pendingByCode.put(normalizeCode(code), new PendingLink(playerId, clean(playerName, playerId.toString()), normalizeCode(code), expiresAt.toEpochMilli()));
        markDirty();
    }

    public CompleteLinkResult completeLink(String code, long chatId, String telegramUsername, Instant now) {
        removeExpiredPendingLinks(now);
        PendingLink pending = pendingByCode.remove(normalizeCode(code));
        if (pending == null) {
            return CompleteLinkResult.failed();
        }

        removeLinkByPlayer(pending.playerId(), false);
        removeLinkByChat(chatId, false);
        LinkRecord link = new LinkRecord(
            pending.playerId(),
            pending.playerName(),
            chatId,
            clean(telegramUsername, ""),
            now.toEpochMilli()
        );
        putLink(link);
        markDirty();
        return CompleteLinkResult.linked(link);
    }

    public boolean unlinkByPlayer(UUID playerId) {
        return removeLinkByPlayer(playerId, true) != null;
    }

    public boolean unlinkByChat(long chatId) {
        return removeLinkByChat(chatId, true) != null;
    }

    public void removeExpiredPendingLinks(Instant now) {
        boolean changed = pendingByCode.entrySet().removeIf(entry -> entry.getValue().isExpiredAt(now));
        if (changed) {
            markDirty();
        }
    }

    private LinkRecord removeLinkByPlayer(UUID playerId, boolean dirty) {
        LinkRecord removed = linksByPlayer.remove(playerId);
        if (removed != null) {
            linksByChat.remove(removed.chatId());
            if (dirty) {
                markDirty();
            }
        }
        return removed;
    }

    private LinkRecord removeLinkByChat(long chatId, boolean dirty) {
        LinkRecord removed = linksByChat.remove(chatId);
        if (removed != null) {
            linksByPlayer.remove(removed.playerId());
            if (dirty) {
                markDirty();
            }
        }
        return removed;
    }

    private void removePendingByPlayer(UUID playerId) {
        pendingByCode.entrySet().removeIf(entry -> entry.getValue().playerId().equals(playerId));
    }

    private void putLink(LinkRecord link) {
        linksByPlayer.put(link.playerId(), link);
        linksByChat.put(link.chatId(), link);
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    public record CompleteLinkResult(boolean success, LinkRecord link) {
        static CompleteLinkResult linked(LinkRecord link) {
            return new CompleteLinkResult(true, link);
        }

        static CompleteLinkResult failed() {
            return new CompleteLinkResult(false, null);
        }
    }

    public record LinkRecord(UUID playerId, String playerName, long chatId, String telegramUsername, long linkedAtEpochMillis) {
        private NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putString(PLAYER_ID_KEY, playerId.toString());
            nbt.putString(PLAYER_NAME_KEY, playerName);
            nbt.putLong(CHAT_ID_KEY, chatId);
            nbt.putString(TELEGRAM_USERNAME_KEY, telegramUsername);
            nbt.putLong(LINKED_AT_KEY, linkedAtEpochMillis);
            return nbt;
        }

        private static LinkRecord fromNbt(NbtCompound nbt) {
            try {
                UUID playerId = UUID.fromString(nbt.getString(PLAYER_ID_KEY));
                return new LinkRecord(
                    playerId,
                    clean(nbt.getString(PLAYER_NAME_KEY), playerId.toString()),
                    nbt.getLong(CHAT_ID_KEY),
                    clean(nbt.getString(TELEGRAM_USERNAME_KEY), ""),
                    nbt.getLong(LINKED_AT_KEY)
                );
            } catch (RuntimeException exception) {
                PowerRubleTelegaMod.logger().warn("Skipping invalid Telegram link entry", exception);
                return null;
            }
        }
    }

    public record PendingLink(UUID playerId, String playerName, String code, long expiresAtEpochMillis) {
        private boolean isExpiredAt(Instant now) {
            return expiresAtEpochMillis <= now.toEpochMilli();
        }

        private NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putString(PLAYER_ID_KEY, playerId.toString());
            nbt.putString(PLAYER_NAME_KEY, playerName);
            nbt.putString(CODE_KEY, code);
            nbt.putLong(EXPIRES_AT_KEY, expiresAtEpochMillis);
            return nbt;
        }

        private static PendingLink fromNbt(NbtCompound nbt) {
            try {
                UUID playerId = UUID.fromString(nbt.getString(PLAYER_ID_KEY));
                String code = normalizeCode(nbt.getString(CODE_KEY));
                if (code.isBlank()) {
                    return null;
                }
                return new PendingLink(
                    playerId,
                    clean(nbt.getString(PLAYER_NAME_KEY), playerId.toString()),
                    code,
                    nbt.getLong(EXPIRES_AT_KEY)
                );
            } catch (RuntimeException exception) {
                PowerRubleTelegaMod.logger().warn("Skipping invalid Telegram pending link entry", exception);
                return null;
            }
        }
    }
}
