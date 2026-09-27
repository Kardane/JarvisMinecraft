package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaStep;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.runtime.AuditSink;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ExecutionPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ScheduledActionService;
import io.github.kardane.jarvisminecraft.common.runtime.SchedulingPolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.earlier;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

final class ToolExecutionCoordinator {
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PRE_AUDIT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration POST_AUDIT_TIMEOUT = Duration.ofSeconds(1);

    private final ExecutionPolicy executionPolicy;
    private final ToolExecutionSupport support;
    private final ScheduledToolCoordinator scheduled;

    ToolExecutionCoordinator(
        String serverId,
        ConversationHistoryStore history,
        LunaClient luna,
        ExecutionPolicy executionPolicy,
        SchedulingPolicy schedulingPolicy,
        ScheduledActionService scheduledActions,
        AuditSink audit,
        CommonRuntime.ExecutionRuntime toolRuntime,
        BrainRuntimeGuard guard,
        Clock clock,
        JarvisLog log
    ) {
        this.executionPolicy = Objects.requireNonNull(
            executionPolicy,
            "executionPolicy"
        );
        this.support = new ToolExecutionSupport(
            serverId,
            history,
            luna,
            executionPolicy,
            audit,
            toolRuntime,
            guard,
            clock,
            log
        );
        this.scheduled = new ScheduledToolCoordinator(
            executionPolicy,
            schedulingPolicy,
            scheduledActions,
            support,
            log
        );
    }

    Set<ToolName> activeTools() {
        return support.activeTools();
    }

    boolean schedulingEnabled() {
        return scheduled.schedulingEnabled();
    }

    void cancelActor(UUID requesterUuid) {
        scheduled.cancelActor(requesterUuid);
    }

    void close() {
        scheduled.close();
    }

    CompletionStage<Void> executeSequentially(
        EmbeddedBrain.ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        List<LunaStep.ToolCall> calls
    ) {
        CompletionStage<Void> chain =
            CompletableFuture.completedFuture(null);
        for (LunaStep.ToolCall call : calls) {
            chain = chain.thenCompose(
                ignored -> executeTool(
                    request,
                    budget,
                    routing,
                    call
                )
            );
        }
        return chain;
    }

    private CompletionStage<Void> executeTool(
        EmbeddedBrain.ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        LunaStep.ToolCall call
    ) {
        support.assertRunning();
        support.assertSession(
            request.requesterUuid(),
            request.sessionId()
        );
        budget.assertLive(support.now());

        ExecutionPolicy.Decision initialDecision =
            executionPolicy.evaluate(
                call.tool(),
                request.toolsAllowed(),
                request.mode()
            );
        if (!initialDecision.allowed()) {
            support.logToolDenied(
                request,
                call.tool(),
                initialDecision.reason(),
                null
            );
            return CompletableFuture.failedFuture(
                new ProtocolException(
                    ErrorCode.UNSUPPORTED,
                    "Tool is no longer allowed by the current execution policy."
                )
            );
        }

        if (
            call.tool() == ToolName.SCHEDULE_ACTION
                || call.tool()
                    == ToolName.CANCEL_SCHEDULED_ACTION
        ) {
            return scheduled.executeControl(
                request,
                budget,
                routing,
                call
            );
        }

        Instant sentAt = support.now();
        Instant toolDeadline = earlier(
            budget.deadlineAt(),
            sentAt.plus(TOOL_TIMEOUT)
        );
        UUID toolCallId = UUID.randomUUID();
        UUID actionId = call.tool().stateChanging()
            ? UUID.randomUUID()
            : null;

        CompletionStage<Void> preAudit =
            call.tool().stateChanging()
                ? support.requirePreAudit(
                    support.auditEvent(
                        request,
                        routing,
                        call,
                        toolCallId,
                        actionId,
                        "PRE_EXECUTION",
                        "BrainPolicy",
                        0L
                    ),
                    earlier(
                        toolDeadline,
                        sentAt.plus(PRE_AUDIT_TIMEOUT)
                    )
                )
                : CompletableFuture.completedFuture(null);

        return preAudit.thenCompose(ignored -> {
            support.assertRunning();
            support.assertSession(
                request.requesterUuid(),
                request.sessionId()
            );

            ExecutionPolicy.Decision currentDecision =
                executionPolicy.evaluate(
                    call.tool(),
                    request.toolsAllowed(),
                    request.mode()
                );
            if (!currentDecision.allowed()) {
                support.logToolDenied(
                    request,
                    call.tool(),
                    ExecutionPolicy.DenialReason.POLICY_CHANGED,
                    currentDecision.reason()
                );
                return CompletableFuture.failedFuture(
                    new ProtocolException(
                        ErrorCode.UNSUPPORTED,
                        "Tool was denied by the current execution policy before execution."
                    )
                );
            }

            support.logToolStarted(
                request,
                call.tool(),
                toolCallId,
                actionId
            );

            CommonRuntime.ToolInvocation invocation =
                new CommonRuntime.ToolInvocation(
                    sentAt,
                    toolDeadline,
                    request.requesterUuid(),
                    request.requestId(),
                    request.sessionId(),
                    toolCallId,
                    actionId,
                    call.tool(),
                    call.arguments()
                );
            Instant startedAt = support.now();

            return support.execute(invocation)
                .thenCompose(result -> {
                    long latency = Math.max(
                        0L,
                        Duration.between(
                            startedAt,
                            support.now()
                        ).toMillis()
                    );

                    support.logToolCompleted(
                        request,
                        call.tool(),
                        toolCallId,
                        actionId,
                        result,
                        latency
                    );

                    return support.tryPostAudit(
                        support.auditEvent(
                            request,
                            routing,
                            call,
                            toolCallId,
                            actionId,
                            result.status().name(),
                            result.source(),
                            latency
                        ),
                        earlier(
                            budget.deadlineAt(),
                            support.now().plus(
                                POST_AUDIT_TIMEOUT
                            )
                        )
                    ).thenApply(postIgnored -> {
                        support.assertSession(
                            request.requesterUuid(),
                            request.sessionId()
                        );
                        support.appendToolResult(
                            request,
                            call,
                            toolCallId,
                            result
                        );
                        return null;
                    });
                });
        });
    }
}
