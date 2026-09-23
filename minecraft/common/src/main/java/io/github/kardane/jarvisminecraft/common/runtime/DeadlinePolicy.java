package io.github.kardane.jarvisminecraft.common.runtime;

import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolMessage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

public final class DeadlinePolicy {
    private final Clock clock;

    public DeadlinePolicy(Clock clock) {
        this.clock = clock;
    }

    public void validate(ProtocolMessage message) {
        if (message.deadlineAt() == null) {
            throw new ProtocolException(ErrorCode.INVALID_ARGUMENT, "Tool request requires deadlineAt.");
        }
        if (!message.deadlineAt().isAfter(message.sentAt())) {
            throw new ProtocolException(ErrorCode.INVALID_ARGUMENT, "deadlineAt must be after sentAt.");
        }
        if (!clock.instant().isBefore(message.deadlineAt())) {
            throw new ProtocolException(ErrorCode.TIMEOUT, "Request deadline has expired.");
        }
    }

    public long remainingMillis(Instant deadlineAt) {
        return Math.max(0L, Duration.between(clock.instant(), deadlineAt).toMillis());
    }

    public Instant now() {
        return clock.instant();
    }
}
