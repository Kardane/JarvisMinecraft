package io.github.kardane.jarvisminecraft.common.brain;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface ConversationMemoryStore {
    CompletionStage<ConversationMemorySnapshot> retrieve(
        UUID requesterUuid,
        UUID currentSessionId,
        UUID currentRequestId,
        String query
    );

    static ConversationMemoryStore disabled() {
        return (
            requesterUuid,
            currentSessionId,
            currentRequestId,
            query
        ) -> {
            Objects.requireNonNull(
                requesterUuid,
                "requesterUuid"
            );
            Objects.requireNonNull(
                currentSessionId,
                "currentSessionId"
            );
            Objects.requireNonNull(
                currentRequestId,
                "currentRequestId"
            );
            Objects.requireNonNull(query, "query");
            return CompletableFuture.completedFuture(
                ConversationMemorySnapshot.empty()
            );
        };
    }
}
