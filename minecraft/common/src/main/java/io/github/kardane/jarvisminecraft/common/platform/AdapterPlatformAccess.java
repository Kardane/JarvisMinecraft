package io.github.kardane.jarvisminecraft.common.platform;

import io.github.kardane.jarvisminecraft.common.chat.PlayerIdentity;

import java.util.Optional;
import java.util.UUID;

public interface AdapterPlatformAccess {
    boolean isServerThread();

    boolean isOnlineOperator(UUID playerUuid);

    /**
     * Returns the current interaction identity for an online player.
     * Platform implementations override this to support non-OP audience modes.
     * The default preserves the historical OP-only contract for test fakes and
     * compatibility implementations.
     */
    default Optional<PlayerIdentity> interactionPlayer(UUID playerUuid) {
        if (!isOnlineOperator(playerUuid)) {
            return Optional.empty();
        }
        return Optional.of(
            new PlayerIdentity(
                playerUuid,
                playerUuid.toString(),
                true,
                true
            )
        );
    }

    void sendPrivatePlain(UUID requesterUuid, String text);

    void sendPublicPlain(String text);
}
