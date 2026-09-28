package io.github.kardane.jarvisminecraft.common.brain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ArchivingConversationHistoryStore
    implements ConversationHistoryStore {
    private final ConversationHistoryStore delegate;
    private final AsyncConversationArchive archive;

    public ArchivingConversationHistoryStore(
        ConversationHistoryStore delegate,
        AsyncConversationArchive archive
    ) {
        this.delegate = Objects.requireNonNull(
            delegate,
            "delegate"
        );
        this.archive = Objects.requireNonNull(
            archive,
            "archive"
        );
    }

    @Override
    public List<ConversationEntry> history(
        UUID requesterUuid,
        UUID sessionId
    ) {
        return delegate.history(
            requesterUuid,
            sessionId
        );
    }

    @Override
    public void append(
        UUID requesterUuid,
        UUID sessionId,
        ConversationEntry entry
    ) {
        delegate.append(
            requesterUuid,
            sessionId,
            entry
        );
        archive.record(
            requesterUuid,
            sessionId,
            entry
        );
    }

    @Override
    public void clearSession(
        UUID requesterUuid,
        UUID sessionId
    ) {
        delegate.clearSession(
            requesterUuid,
            sessionId
        );
    }

    @Override
    public void clearActor(UUID requesterUuid) {
        delegate.clearActor(requesterUuid);
    }
}
