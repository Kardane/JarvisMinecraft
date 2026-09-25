package io.github.kardane.jarvisminecraft.common.platform;

import java.util.UUID;

public interface AdapterPlatformAccess {
    boolean isServerThread();

    boolean isOnlineOperator(UUID playerUuid);

    void sendPrivatePlain(UUID requesterUuid, String text);
}
