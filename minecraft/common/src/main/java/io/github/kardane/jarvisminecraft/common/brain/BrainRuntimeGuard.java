package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

final class BrainRuntimeGuard {
    private final ChatSessionManager sessions;
    private final BooleanSupplier stopped;

    BrainRuntimeGuard(
        ChatSessionManager sessions,
        BooleanSupplier stopped
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.stopped = Objects.requireNonNull(stopped, "stopped");
    }

    boolean stopped() {
        return stopped.getAsBoolean();
    }

    void assertRunning() {
        if (stopped()) {
            throw new ProtocolException(
                ErrorCode.CANCELLED,
                "Embedded Brain is stopped."
            );
        }
    }

    void assertSession(UUID requesterUuid, UUID sessionId) {
        if (!sessions.isActive(requesterUuid, sessionId)) {
            throw new ProtocolException(
                ErrorCode.CANCELLED,
                "Conversation session is no longer active."
            );
        }
    }
}
