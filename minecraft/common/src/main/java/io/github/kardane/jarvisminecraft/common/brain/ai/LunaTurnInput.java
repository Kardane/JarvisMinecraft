package io.github.kardane.jarvisminecraft.common.brain.ai;

import io.github.kardane.jarvisminecraft.common.brain.ConversationEntry;
import io.github.kardane.jarvisminecraft.common.brain.Capability;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public record LunaTurnInput(
    UUID requestId,
    String requesterName,
    List<ConversationEntry> history,
    List<Capability> capabilities,
    Set<ToolName> availableTools,
    int remainingToolCalls,
    int remainingModelRounds,
    Instant deadlineAt
) {
    public LunaTurnInput {
        Objects.requireNonNull(requestId, "requestId");
        requesterName = Objects.requireNonNull(requesterName, "requesterName");
        history = List.copyOf(Objects.requireNonNull(history, "history"));
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        availableTools = Set.copyOf(Objects.requireNonNull(availableTools, "availableTools"));
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (remainingToolCalls < 0 || remainingModelRounds < 0) {
            throw new IllegalArgumentException("Remaining Luna budgets must not be negative.");
        }
    }
}
