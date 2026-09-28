package io.github.kardane.jarvisminecraft.common.chat;

import java.util.UUID;

public record InteractionDecision(
    Kind kind,
    UUID sessionId,
    String mode,
    String text,
    boolean started,
    boolean accessRevoked,
    int followUpSeconds
) {
    public enum Kind {
        PUBLIC_CHAT,
        PUBLIC_ESCAPE,
        FOLLOW_UP_CANDIDATE,
        FORWARD,
        END
    }

    static InteractionDecision publicChat(
        String text,
        boolean accessRevoked
    ) {
        return new InteractionDecision(
            Kind.PUBLIC_CHAT,
            null,
            null,
            text,
            false,
            accessRevoked,
            0
        );
    }

    static InteractionDecision publicEscape(
        UUID sessionId,
        String text
    ) {
        return new InteractionDecision(
            Kind.PUBLIC_ESCAPE,
            sessionId,
            null,
            text,
            false,
            false,
            0
        );
    }

    static InteractionDecision followUpCandidate(
        UUID sessionId,
        String text,
        int followUpSeconds
    ) {
        return new InteractionDecision(
            Kind.FOLLOW_UP_CANDIDATE,
            sessionId,
            "FOLLOW_UP_CANDIDATE",
            text,
            false,
            false,
            followUpSeconds
        );
    }

    static InteractionDecision forward(
        UUID sessionId,
        String mode,
        String text,
        boolean started,
        int followUpSeconds
    ) {
        return new InteractionDecision(
            Kind.FORWARD,
            sessionId,
            mode,
            text,
            started,
            false,
            followUpSeconds
        );
    }

    static InteractionDecision end(UUID sessionId) {
        return new InteractionDecision(
            Kind.END,
            sessionId,
            null,
            "",
            false,
            false,
            0
        );
    }
}
