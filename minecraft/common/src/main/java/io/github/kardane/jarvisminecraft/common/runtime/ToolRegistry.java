package io.github.kardane.jarvisminecraft.common.runtime;

import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class ToolRegistry {
    private final Map<ToolName, Entry<?>> entries = new EnumMap<>(ToolName.class);

    public <A extends ToolArguments> void register(
        ToolName tool,
        Class<A> argumentType,
        ToolHandler<A> handler
    ) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(argumentType, "argumentType");
        Objects.requireNonNull(handler, "handler");
        if (entries.putIfAbsent(tool, new Entry<>(argumentType, handler)) != null) {
            throw new IllegalStateException("Tool already registered: " + tool.wireName());
        }
    }

    public boolean contains(ToolName tool) {
        return entries.containsKey(tool);
    }

    /**
     * Adds every Tool from a staged registry, or adds none if any Tool conflicts.
     */
    public void registerAll(ToolRegistry stagedRegistry) {
        Objects.requireNonNull(stagedRegistry, "stagedRegistry");
        if (stagedRegistry == this) {
            throw new IllegalArgumentException("A registry cannot be merged into itself.");
        }
        for (ToolName tool : stagedRegistry.entries.keySet()) {
            if (entries.containsKey(tool)) {
                throw new IllegalStateException("Tool already registered: " + tool.wireName());
            }
        }
        entries.putAll(stagedRegistry.entries);
    }

    public Set<ToolName> tools() {
        return Set.copyOf(entries.keySet());
    }

    public CompletionStage<ToolResult> execute(
        ToolName tool,
        ToolExecutionContext context,
        ToolArguments arguments
    ) {
        Entry<?> raw = entries.get(tool);
        if (raw == null) {
            throw new ProtocolException(ErrorCode.UNSUPPORTED, "Tool is not registered.");
        }
        return raw.execute(context, arguments);
    }

    @FunctionalInterface
    public interface ToolHandler<A extends ToolArguments> {
        CompletionStage<ToolResult> execute(ToolExecutionContext context, A arguments);
    }

    public record ToolExecutionContext(
        String serverId,
        java.util.UUID connectionId,
        java.util.UUID requesterUuid,
        java.util.UUID requestId,
        java.util.UUID sessionId,
        java.util.UUID toolCallId,
        java.util.UUID actionId,
        java.time.Instant deadlineAt
    ) {
    }

    private record Entry<A extends ToolArguments>(
        Class<A> argumentType,
        ToolHandler<A> handler
    ) {
        CompletionStage<ToolResult> execute(
            ToolExecutionContext context,
            ToolArguments arguments
        ) {
            if (!argumentType.isInstance(arguments)) {
                throw new ProtocolException(
                    ErrorCode.INVALID_ARGUMENT,
                    "Tool argument type does not match registry contract."
                );
            }
            return handler.execute(context, argumentType.cast(arguments));
        }
    }
}
