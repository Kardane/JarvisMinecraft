package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.generated.GeneratedContractConstants;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;

import java.time.Instant;
import java.util.Objects;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

public final class RequestBudget {
    public static final int MAX_TOOL_CALLS =
        GeneratedContractConstants.MAX_TOOL_CALLS_PER_REQUEST;
    public static final int MAX_MODEL_ROUNDS =
        GeneratedContractConstants.MAX_MODEL_ROUND_TRIPS_PER_REQUEST;
    public static final long MAX_REQUEST_MILLIS =
        GeneratedContractConstants.REQUEST_DEADLINE_MILLIS;

    private final Instant deadlineAt;
    private int toolCalls;
    private int modelRounds;

    public RequestBudget(Instant adapterDeadlineAt, Instant startedAt) {
        Objects.requireNonNull(adapterDeadlineAt, "adapterDeadlineAt");
        Objects.requireNonNull(startedAt, "startedAt");
        Instant localDeadline = startedAt.plusMillis(MAX_REQUEST_MILLIS);
        this.deadlineAt = adapterDeadlineAt.isBefore(localDeadline)
            ? adapterDeadlineAt
            : localDeadline;
    }

    public synchronized void assertLive(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!now.isBefore(deadlineAt)) {
            throw new ProtocolException(
                ErrorCode.TIMEOUT,
                "Request deadline has expired."
            );
        }
    }

    public synchronized void consumeModelRound(Instant now) {
        assertLive(now);
        if (modelRounds >= MAX_MODEL_ROUNDS) {
            throw new ProtocolException(
                ErrorCode.BUSY,
                "Model round budget is exhausted."
            );
        }
        modelRounds += 1;
    }

    public synchronized void consumeToolCalls(int count, Instant now) {
        assertLive(now);
        if (count < 1) {
            throw new ProtocolException(
                ErrorCode.INVALID_ARGUMENT,
                "Model returned an invalid Tool call batch."
            );
        }
        if (toolCalls + count > MAX_TOOL_CALLS) {
            throw new ProtocolException(
                ErrorCode.BUSY,
                "Tool call budget is exhausted."
            );
        }
        toolCalls += count;
    }

    public Instant deadlineAt() {
        return deadlineAt;
    }

    public synchronized int remainingToolCalls() {
        return MAX_TOOL_CALLS - toolCalls;
    }

    public synchronized int remainingModelRounds() {
        return MAX_MODEL_ROUNDS - modelRounds;
    }
}
