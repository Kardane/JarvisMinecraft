package io.github.kardane.jarvisminecraft.common.brain;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class InMemoryConversationHistoryStore implements ConversationHistoryStore {
    public static final int DEFAULT_MAX_ENTRIES = 32;

    private final int maxEntries;
    private final Map<SessionKey, ArrayDeque<ConversationEntry>> histories = new HashMap<>();

    public InMemoryConversationHistoryStore() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public InMemoryConversationHistoryStore(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive.");
        }
        this.maxEntries = maxEntries;
    }

    @Override
    public synchronized List<ConversationEntry> history(UUID requesterUuid, UUID sessionId) {
        SessionKey key = key(requesterUuid, sessionId);
        ArrayDeque<ConversationEntry> history = histories.get(key);
        return history == null ? List.of() : List.copyOf(history);
    }

    @Override
    public synchronized void append(
        UUID requesterUuid,
        UUID sessionId,
        ConversationEntry entry
    ) {
        SessionKey key = key(requesterUuid, sessionId);
        Objects.requireNonNull(entry, "entry");

        ArrayDeque<ConversationEntry> history =
            histories.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        history.addLast(entry);
        while (history.size() > maxEntries) {
            history.removeFirst();
        }
    }

    @Override
    public synchronized void clearSession(UUID requesterUuid, UUID sessionId) {
        histories.remove(key(requesterUuid, sessionId));
    }

    @Override
    public synchronized void clearActor(UUID requesterUuid) {
        Objects.requireNonNull(requesterUuid, "requesterUuid");
        histories.keySet().removeIf(key -> key.requesterUuid().equals(requesterUuid));
    }

    private SessionKey key(UUID requesterUuid, UUID sessionId) {
        return new SessionKey(
            Objects.requireNonNull(requesterUuid, "requesterUuid"),
            Objects.requireNonNull(sessionId, "sessionId")
        );
    }

    private record SessionKey(UUID requesterUuid, UUID sessionId) {
    }
}
