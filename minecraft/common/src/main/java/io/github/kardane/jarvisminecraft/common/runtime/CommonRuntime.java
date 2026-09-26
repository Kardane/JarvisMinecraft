package io.github.kardane.jarvisminecraft.common.runtime;

import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ActionState;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
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
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.deadlines = new DeadlinePolicy(Objects.requireNonNull(clock, "clock"));
        this.actions = new DeduplicationLedger(4096);
    }

    /**
     * Creates an execution scope. The runtimeId is a process-local generation identifier used
     * only for deduplication ownership across Embedded Brain lifecycles.
     */
    public ExecutionRuntime openRuntime(
        UUID runtimeId,
        String serverId,
        Set<ToolName> activeTools
    ) {
        if (runtimeId == null) {
            throw new IllegalArgumentException("runtimeId");
        }
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId");
        }
        Objects.requireNonNull(activeTools, "activeTools");
        return new ExecutionRuntime(
            runtimeId,
            serverId,
            activeTools.isEmpty() ? Set.of() : EnumSet.copyOf(activeTools)
        );
    }

    public record ToolInvocation(
        Instant sentAt,
        Instant deadlineAt,
        UUID requesterUuid,
        UUID requestId,
        UUID sessionId,
        UUID toolCallId,
        UUID actionId,
        ToolName tool,
        ToolArguments arguments
    ) {
        public ToolInvocation {
            Objects.requireNonNull(sentAt, "sentAt");
            Objects.requireNonNull(deadlineAt, "deadlineAt");
            Objects.requireNonNull(requesterUuid, "requesterUuid");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    public final class ExecutionRuntime {
        private final UUID runtimeId;
        private final String serverId;
        private final Set<ToolName> activeTools;
        private final ToolCallLedger toolCalls = new ToolCallLedger(4096);

        private ExecutionRuntime(
            UUID runtimeId,
            String serverId,
            Set<ToolName> activeTools
        ) {
            this.runtimeId = runtimeId;
            this.serverId = serverId;
            this.activeTools = Set.copyOf(activeTools);
        }

        public UUID runtimeId() {
            return runtimeId;
        }

        public String serverId() {
            return serverId;
        }

        public Set<ToolName> activeTools() {
            return activeTools;
        }

        public CompletionStage<ToolResult> execute(ToolInvocation invocation) {
            try {
                validate(invocation);

                ToolName tool = invocation.tool();
                if (!activeTools.contains(tool) || !registry.contains(tool)) {
                    throw new ProtocolException(
                        ErrorCode.UNSUPPORTED,
                        "Tool is not active on this server."
                    );
                }

                if (!authority.isOnlineOperator(invocation.requesterUuid())) {
                    throw new ProtocolException(
                        ErrorCode.UNAUTHORIZED,
                        "Requester is not a current online operator."
                    );
                }

                ToolCallStart toolStart = toolCalls.begin(invocation.toolCallId());
                if (toolStart.kind() == ToolCallStart.Kind.IN_FLIGHT) {
                    return completed(
                        error(ErrorCode.BUSY, "Tool call is already executing.", true)
                    );
                }
                if (toolStart.kind() == ToolCallStart.Kind.CACHED) {
                    return completed(toolStart.result());
                }

                if (tool.stateChanging()) {
                    ActionStart actionStart =
                        actions.beginAction(invocation.actionId(), runtimeId);
                    switch (actionStart.kind()) {
                        case IN_FLIGHT -> {
                            ToolResult result =
                                error(ErrorCode.BUSY, "Action is already executing.", true);
                            toolCalls.complete(invocation.toolCallId(), result);
                            return completed(result);
                        }
                        case CACHED -> {
                            toolCalls.complete(
                                invocation.toolCallId(),
                                actionStart.result()
                            );
                            return completed(actionStart.result());
                        }
                        case STALE_CONNECTION -> {
                            ToolResult result = error(
                                ErrorCode.CANCELLED,
                                "Action belongs to a previous runtime generation and will not be replayed.",
                                false
                            );
                            toolCalls.complete(invocation.toolCallId(), result);
                            return completed(result);
                        }
                        case FRESH -> {
                            // Continue.
                        }
                    }
                }

                ToolExecutionContext context = new ToolExecutionContext(
                    serverId,
                    runtimeId,
                    invocation.requesterUuid(),
                    invocation.requestId(),
                    invocation.sessionId(),
                    invocation.toolCallId(),
                    invocation.actionId(),
                    invocation.deadlineAt()
                );

                CompletionStage<ToolResult> scheduled;
                try {
                    scheduled = scheduler.submit(
                        () -> registry.execute(
                            tool,
                            context,
                            invocation.arguments()
                        )
                    );
                } catch (RuntimeException failure) {
                    ToolResult result = error(
                        ErrorCode.INTERNAL,
                        "Scheduler rejected Tool execution.",
                        false
                    );
                    settle(invocation, result, ActionState.FAILED);
                    return completed(result);
                }

                return raceDeadline(invocation, scheduled);
            } catch (ProtocolException failure) {
                return completed(
                    error(failure.code(), failure.getMessage(), false)
                );
            } catch (RuntimeException failure) {
                return completed(
                    error(
                        ErrorCode.INTERNAL,
                        "Internal Tool execution failure.",
                        false
                    )
                );
            }
        }

        private void validate(ToolInvocation invocation) {
            Objects.requireNonNull(invocation, "invocation");
            deadlines.validate(invocation.sentAt(), invocation.deadlineAt());

            if (invocation.tool().stateChanging() && invocation.actionId() == null) {
                throw new ProtocolException(
                    ErrorCode.INVALID_ARGUMENT,
                    "State-changing Tool requires actionId."
                );
            }
            if (!invocation.tool().stateChanging() && invocation.actionId() != null) {
                throw new ProtocolException(
                    ErrorCode.INVALID_ARGUMENT,
                    "Read-only Tool requires actionId=null."
                );
            }
        }

        private CompletionStage<ToolResult> raceDeadline(
            ToolInvocation invocation,
            CompletionStage<ToolResult> scheduled
        ) {
            CompletableFuture<ToolResult> output = new CompletableFuture<>();
            long delay = deadlines.remainingMillis(invocation.deadlineAt());

            scheduled.whenComplete((result, failure) -> {
                ToolResult terminal = failure == null
                    ? result
                    : error(
                        ErrorCode.INTERNAL,
                        "Tool execution failed.",
                        false
                    );
                ActionState state =
                    terminal.status()
                        == io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus.OK
                        || terminal.status()
                        == io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus.EMPTY
                    ? ActionState.SUCCEEDED
                    : ActionState.FAILED;
                if (output.complete(terminal)) {
                    settle(invocation, terminal, state);
                }
            });

            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    ToolResult timeout = invocation.tool().stateChanging()
                        ? error(
                            ErrorCode.OUTCOME_UNKNOWN,
                            "State-changing Tool did not produce a result before the deadline.",
                            false
                        )
                        : error(
                            ErrorCode.TIMEOUT,
                            "Tool execution exceeded its deadline.",
                            true
                        );
                    if (output.complete(timeout)) {
                        settle(
                            invocation,
                            timeout,
                            invocation.tool().stateChanging()
                                ? ActionState.OUTCOME_UNKNOWN
                                : ActionState.FAILED
                        );
                    }
                });

            return output;
        }

        private void settle(
            ToolInvocation invocation,
            ToolResult result,
            ActionState actionState
        ) {
            toolCalls.complete(invocation.toolCallId(), result);
            if (invocation.tool().stateChanging()) {
                actions.completeAction(
                    invocation.actionId(),
                    runtimeId,
                    actionState,
                    result
                );
            }
        }

        private CompletionStage<ToolResult> completed(ToolResult result) {
            return CompletableFuture.completedFuture(result);
        }
    }

    private ToolResult error(
        ErrorCode code,
        String message,
        boolean retryable
    ) {
        return ToolResult.error(
            code,
            message,
            retryable,
            deadlines.now(),
            COMMON_SOURCE
        );
    }
}
