package io.github.kardane.jarvisminecraft.common.brain;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    default CompletionStage<Boolean> considerFollowUp(
        UUID requesterUuid,
        String requesterName,
        UUID sessionId,
        String text
    ) {
        return CompletableFuture.completedFuture(false);
    }

    default CompletionStage<Boolean> considerProactive(
        UUID requesterUuid,
        String requesterName,
        String text
    ) {
        return CompletableFuture.completedFuture(false);
    }

    default StatusSnapshot status() {
        return StatusSnapshot.unavailable();
    }

    void cancelActor(UUID requesterUuid, CancelReason reason);

    void cancelSession(UUID requesterUuid, UUID sessionId, CancelReason reason);

    void start();

    void stop();

    record StatusSnapshot(
        String runtime,
        String interactionMode,
        String audienceMode,
        String executionMode,
        boolean schedulingEnabled,
        int aiQueued,
        int aiQueueCapacity,
        int aiActive,
        int aiConcurrentCapacity,
        boolean proactiveInFlight,
        AuditHealth audit
    ) {
        public static StatusSnapshot unavailable() {
            return new StatusSnapshot(
                "UNAVAILABLE",
                "UNKNOWN",
                "UNKNOWN",
                "UNKNOWN",
                false,
                0,
                0,
                0,
                0,
                false,
                AuditHealth.unavailable()
            );
        }
    }

    record AuditHealth(
        String status,
        boolean writable,
        boolean closed,
        int queueDepth,
        int maxQueue,
        long rejectedRecords,
        Instant lastSuccessfulWriteAt,
        Instant lastErrorAt,
        String lastErrorCode,
        long totalBytes,
        int fileCount
    ) {
        public static AuditHealth unavailable() {
            return new AuditHealth(
                "UNAVAILABLE",
                false,
                false,
                0,
                0,
                0L,
                null,
                null,
                null,
                0L,
                0
            );
        }
    }
}
