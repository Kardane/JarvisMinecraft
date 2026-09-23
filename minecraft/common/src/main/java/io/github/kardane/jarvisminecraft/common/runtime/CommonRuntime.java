package io.github.kardane.jarvisminecraft.common.runtime;

import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolMessage;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ActionState;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.MessageType;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;
import static io.github.kardane.jarvisminecraft.common.runtime.DeduplicationLedger.ActionStart;
import static io.github.kardane.jarvisminecraft.common.runtime.DeduplicationLedger.ToolCallLedger;
import static io.github.kardane.jarvisminecraft.common.runtime.DeduplicationLedger.ToolCallStart;
import static io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry.ToolExecutionContext;

public final class CommonRuntime {
    private static final String COMMON_SOURCE = "JarvisCommon";

    private final ToolRegistry registry;
    private final ServerScheduler scheduler;
    private final RequesterAuthority authority;
    private final DeadlinePolicy deadlines;
    private final DeduplicationLedger actions;

    public CommonRuntime(
        ToolRegistry registry,
        ServerScheduler scheduler,
        RequesterAuthority authority,
        Clock clock
    ) {
        this.registry = registry;
        this.scheduler = scheduler;
        this.authority = authority;
        this.deadlines = new DeadlinePolicy(clock);
        this.actions = new DeduplicationLedger(4096);
    }

    public ConnectionRuntime openConnection(
        UUID connectionId,
        String serverId,
        Set<ToolName> activeTools
    ) {
        if (connectionId == null) {
            throw new IllegalArgumentException("connectionId");
        }
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId");
        }
        return new ConnectionRuntime(
            connectionId,
            serverId,
            activeTools.isEmpty() ? Set.of() : EnumSet.copyOf(activeTools)
        );
    }

    public final class ConnectionRuntime {
        private final UUID connectionId;
        private final String serverId;
        private final Set<ToolName> activeTools;
        private final ToolCallLedger toolCalls = new ToolCallLedger(4096);

        private ConnectionRuntime(UUID connectionId, String serverId, Set<ToolName> activeTools) {
            this.connectionId = connectionId;
            this.serverId = serverId;
            this.activeTools = Set.copyOf(activeTools);
        }

        public UUID connectionId() {
            return connectionId;
        }

        public CompletionStage<ToolResult> execute(ProtocolMessage message) {
            try {
                if (message.type() != MessageType.TOOL_REQUEST) {
                    throw new ProtocolException(ErrorCode.INVALID_ARGUMENT, "Expected tool.request.");
                }
                if (!serverId.equals(message.serverId())) {
                    throw new ProtocolException(ErrorCode.UNAUTHORIZED, "serverId does not match connection binding.");
                }
                if (message.requestId() == null || message.sessionId() == null
                    || message.requesterUuid() == null || message.toolCallId() == null) {
                    throw new ProtocolException(ErrorCode.INVALID_ARGUMENT, "Missing Tool binding identifiers.");
                }

                deadlines.validate(message);

                ProtocolMessage.ToolRequest request = (ProtocolMessage.ToolRequest) message.payload();
                ToolName tool = request.tool();
                if (tool.stateChanging() && message.actionId() == null) {
                    throw new ProtocolException(ErrorCode.INVALID_ARGUMENT, "State-changing Tool requires actionId.");
                }
                if (!tool.stateChanging() && message.actionId() != null) {
                    throw new ProtocolException(ErrorCode.INVALID_ARGUMENT, "Read-only Tool requires actionId=null.");
                }
                if (!activeTools.contains(tool) || !registry.contains(tool)) {
                    throw new ProtocolException(ErrorCode.UNSUPPORTED, "Tool is not active on this server.");
                }

                if (!authority.isOnlineOperator(message.requesterUuid())) {
                    throw new ProtocolException(ErrorCode.UNAUTHORIZED, "Requester is not a current online operator.");
                }

                ToolCallStart toolStart = toolCalls.begin(message.toolCallId());
                if (toolStart.kind() == ToolCallStart.Kind.IN_FLIGHT) {
                    return completed(error(ErrorCode.BUSY, "Tool call is already executing.", true));
                }
                if (toolStart.kind() == ToolCallStart.Kind.CACHED) {
                    return completed(toolStart.result());
                }

                if (tool.stateChanging()) {
                    ActionStart actionStart = actions.beginAction(message.actionId(), connectionId);
                    switch (actionStart.kind()) {
                        case IN_FLIGHT -> {
                            ToolResult result = error(ErrorCode.BUSY, "Action is already executing.", true);
                            toolCalls.complete(message.toolCallId(), result);
                            return completed(result);
                        }
                        case CACHED -> {
                            toolCalls.complete(message.toolCallId(), actionStart.result());
                            return completed(actionStart.result());
                        }
                        case STALE_CONNECTION -> {
                            ToolResult result = error(
                                ErrorCode.CANCELLED,
                                "Action belongs to a previous connection and will not be replayed.",
                                false
                            );
                            toolCalls.complete(message.toolCallId(), result);
                            return completed(result);
                        }
                        case FRESH -> {
                            // Continue.
                        }
                    }
                }

                ToolExecutionContext context = new ToolExecutionContext(
                    serverId,
                    connectionId,
                    message.requesterUuid(),
                    message.requestId(),
                    message.sessionId(),
                    message.toolCallId(),
                    message.actionId(),
                    message.deadlineAt()
                );

                CompletionStage<ToolResult> scheduled;
                try {
                    scheduled = scheduler.submit(() -> registry.execute(tool, context, request.arguments()));
                } catch (RuntimeException e) {
                    ToolResult result = error(ErrorCode.INTERNAL, "Scheduler rejected Tool execution.", false);
                    settle(message, tool, result, ActionState.FAILED);
                    return completed(result);
                }

                return raceDeadline(message, tool, scheduled);
            } catch (ProtocolException e) {
                return completed(error(e.code(), e.getMessage(), false));
            } catch (RuntimeException e) {
                return completed(error(ErrorCode.INTERNAL, "Internal Tool execution failure.", false));
            }
        }

        private CompletionStage<ToolResult> raceDeadline(
            ProtocolMessage message,
            ToolName tool,
            CompletionStage<ToolResult> scheduled
        ) {
            CompletableFuture<ToolResult> output = new CompletableFuture<>();
            long delay = deadlines.remainingMillis(message.deadlineAt());

            scheduled.whenComplete((result, failure) -> {
                ToolResult terminal = failure == null
                    ? result
                    : error(ErrorCode.INTERNAL, "Tool execution failed.", false);
                ActionState state = terminal.status() == io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus.OK
                    || terminal.status() == io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus.EMPTY
                    ? ActionState.SUCCEEDED
                    : ActionState.FAILED;
                if (output.complete(terminal)) {
                    settle(message, tool, terminal, state);
                }
            });

            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
                ToolResult timeout = tool.stateChanging()
                    ? error(
                        ErrorCode.OUTCOME_UNKNOWN,
                        "State-changing Tool did not produce a result before the deadline.",
                        false
                    )
                    : error(ErrorCode.TIMEOUT, "Tool execution exceeded its deadline.", true);
                if (output.complete(timeout)) {
                    settle(
                        message,
                        tool,
                        timeout,
                        tool.stateChanging() ? ActionState.OUTCOME_UNKNOWN : ActionState.FAILED
                    );
                }
            });

            return output;
        }

        private void settle(
            ProtocolMessage message,
            ToolName tool,
            ToolResult result,
            ActionState actionState
        ) {
            toolCalls.complete(message.toolCallId(), result);
            if (tool.stateChanging()) {
                actions.completeAction(message.actionId(), connectionId, actionState, result);
            }
        }

        private CompletionStage<ToolResult> completed(ToolResult result) {
            return CompletableFuture.completedFuture(result);
        }

        private ToolResult error(ErrorCode code, String message, boolean retryable) {
            return ToolResult.error(code, message, retryable, deadlines.now(), COMMON_SOURCE);
        }
    }
}
