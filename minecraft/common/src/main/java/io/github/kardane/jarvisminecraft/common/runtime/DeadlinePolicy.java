package io.github.kardane.jarvisminecraft.common.runtime;

import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolMessage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

public final class DeadlinePolicy {
    private final Clock clock;

    public DeadlinePolicy(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void validate(ProtocolMessage message) {
        Objects.requireNonNull(message, "message");
        validate(message.sentAt(), message.deadlineAt());
    }

    public void validate(Instant sentAt, Instant deadlineAt) {
        if (sentAt == null || deadlineAt == null) {
            throw new ProtocolException(
                ErrorCode.INVALID_ARGUMENT,
                "Tool request requires sentAt and deadlineAt."
            );
        }
        if (!deadlineAt.isAfter(sentAt)) {
            throw new ProtocolException(
                ErrorCode.INVALID_ARGUMENT,
                "deadlineAt must be after sentAt."
            );
        }
        if (!clock.instant().isBefore(deadlineAt)) {
            throw new ProtocolException(
                ErrorCode.TIMEOUT,
                "Request deadline has expired."
            );
        }
    }

    public long remainingMillis(Instant deadlineAt) {
        return Math.max(0L, Duration.between(clock.instant(), deadlineAt).toMillis());
    }

    public Instant now() {
        return clock.instant();
    }
}
