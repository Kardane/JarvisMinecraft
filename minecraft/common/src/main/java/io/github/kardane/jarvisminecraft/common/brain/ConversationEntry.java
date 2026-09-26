package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public sealed interface ConversationEntry
    permits ConversationEntry.UserMessage, ConversationEntry.AssistantMessage, ConversationEntry.ToolMessage {

    UUID requestId();

    Instant at();

    record UserMessage(
        String text,
        UUID requestId,
        Instant at
    ) implements ConversationEntry {
        public UserMessage {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(at, "at");
        }
    }

    record AssistantMessage(
        String text,
        UUID requestId,
        Instant at
    ) implements ConversationEntry {
        public AssistantMessage {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(at, "at");
        }
    }

    record ToolMessage(
        ToolName tool,
        UUID toolCallId,
        ToolResult result,
        UUID requestId,
        Instant at
    ) implements ConversationEntry {
        public ToolMessage {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(at, "at");
        }
    }
}
