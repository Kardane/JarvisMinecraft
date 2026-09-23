package io.github.kardane.jarvisminecraft.common.runtime;

import java.util.UUID;

@FunctionalInterface
public interface RequesterAuthority {
    /**
     * Must consult the platform's current online operator registry.
     * Cached/model-provided OP state is not authoritative.
     */
    boolean isOnlineOperator(UUID requesterUuid);
}
