package io.github.kardane.jarvisminecraft.common.chat;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AmbientChatMessage(
    UUID senderUuid,
    String senderName,
    String text,
    Instant observedAt
) {
    public AmbientChatMessage {
        Objects.requireNonNull(senderUuid, "senderUuid");
        senderName = Objects.requireNonNull(
            senderName,
            "senderName"
        );
        text = Objects.requireNonNull(text, "text");
        Objects.requireNonNull(observedAt, "observedAt");
        if (senderName.isBlank()) {
            throw new IllegalArgumentException(
                "senderName must not be blank."
            );
        }
    }
}
