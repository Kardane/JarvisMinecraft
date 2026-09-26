package io.github.kardane.jarvisminecraft.common.brain;

import java.util.List;
import java.util.UUID;

public interface ConversationHistoryStore {
    List<ConversationEntry> history(UUID requesterUuid, UUID sessionId);

    void append(UUID requesterUuid, UUID sessionId, ConversationEntry entry);

    void clearSession(UUID requesterUuid, UUID sessionId);

    void clearActor(UUID requesterUuid);
}
