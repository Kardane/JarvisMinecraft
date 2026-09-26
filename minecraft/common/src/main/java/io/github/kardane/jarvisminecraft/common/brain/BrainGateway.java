package io.github.kardane.jarvisminecraft.common.brain;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;

public interface BrainGateway {
    CompletionStage<Boolean> submitChat(
        UUID requesterUuid,
        String requesterName,
        UUID sessionId,
        String mode,
        String text
    );

    void cancelActor(UUID requesterUuid, CancelReason reason);

    void cancelSession(UUID requesterUuid, UUID sessionId, CancelReason reason);

    void start();

    void stop();
}
