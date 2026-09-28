package io.github.kardane.jarvisminecraft.common.brain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record ConversationMemorySnapshot(
    String context,
    int turnCount
) {
    public ConversationMemorySnapshot {
        context = Objects.requireNonNull(
            context,
            "context"
        );
        if (turnCount < 0) {
            throw new IllegalArgumentException(
                "turnCount must not be negative."
            );
        }
    }

    public static ConversationMemorySnapshot empty() {
        return new ConversationMemorySnapshot("", 0);
    }

    public boolean emptyMemory() {
        return context.isBlank() || turnCount == 0;
    }

    public int utf8Bytes() {
        return context.getBytes(
            StandardCharsets.UTF_8
        ).length;
    }
}
