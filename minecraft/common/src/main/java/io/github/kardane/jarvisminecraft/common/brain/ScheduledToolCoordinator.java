package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaStep;
import io.github.kardane.jarvisminecraft.common.logging.JarvisEvents;
import io.github.kardane.jarvisminecraft.common.logging.JarvisFields;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.CancelScheduledActionArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.CancelScheduledActionData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ScheduleActionArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ScheduledActionData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ExecutionPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ScheduledActionService;
import io.github.kardane.jarvisminecraft.common.runtime.SchedulingPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.earlier;
import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.unwrap;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

final class ScheduledToolCoordinator {
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PRE_AUDIT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration POST_AUDIT_TIMEOUT = Duration.ofSeconds(1);

    private final ExecutionPolicy executionPolicy;
    private final SchedulingPolicy schedulingPolicy;
    private final ScheduledActionService scheduledActions;
    private final ToolExecutionSupport support;
    private final JarvisLog log;

    ScheduledToolCoordinator(
        ExecutionPolicy executionPolicy,
        SchedulingPolicy schedulingPolicy,
        ScheduledActionService scheduledActions,
        ToolExecutionSupport support,
        JarvisLog log
    ) {
        this.executionPolicy = Objects.requireNonNull(
            executionPolicy,
            "executionPolicy"
        );
        this.schedulingPolicy = Objects.requireNonNull(
            schedulingPolicy,
            "schedulingPolicy"
        );
        this.scheduledActions = Objects.requireNonNull(
            scheduledActions,
            "scheduledActions"
        );
        this.support = Objects.requireNonNull(support, "support");
        this.log = Objects.requireNonNull(log, "log");
    }

    boolean schedulingEnabled() {
        return schedulingPolicy.schedulingEnabled();
    }

    void cancelActor(UUID requesterUuid) {
        List<UUID> cancelledSchedules =
            scheduledActions.cancelActor(requesterUuid);
        for (UUID scheduleId : cancelledSchedules) {
            log.warn(
                JarvisEvents.SCHEDULE_ABORTED,
                JarvisFields.of(
                    "scheduleId", scheduleId,
                    "requesterUuid", requesterUuid,
                    "reason", "ACTOR_INVALIDATED"
                )
            );
        }
    }

    void close() {
        List<UUID> pendingSchedules =
            scheduledActions.pendingScheduleIds();
        scheduledActions.close();
        for (UUID scheduleId : pendingSchedules) {
            log.warn(
                JarvisEvents.SCHEDULE_ABORTED,
                JarvisFields.of(
                    "scheduleId", scheduleId,
                    "reason", "SERVER_STOPPING"
                )
            );
        }
    }

    CompletionStage<Void> executeControl(
        EmbeddedBrain.ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        LunaStep.ToolCall call
    ) {
        return switch (call.tool()) {
            case SCHEDULE_ACTION ->
                scheduleAction(
                    request,
                    budget,
                    routing,
                    call
                );
            case CANCEL_SCHEDULED_ACTION ->
                cancelScheduledAction(
                    request,
                    budget,
                    routing,
                    call
                );
            default -> throw new IllegalArgumentException(
                "Tool is not a scheduling control Tool."
            );
        };
    }

    private CompletionStage<Void> scheduleAction(
        EmbeddedBrain.ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        LunaStep.ToolCall call
    ) {
        ScheduleActionArguments arguments =
            (ScheduleActionArguments) call.arguments();
        schedulingPolicy.validateRegistration(arguments);

        if (!support.activeTools().contains(arguments.tool())) {
            support.logToolDenied(
                request,
                arguments.tool(),
                ExecutionPolicy.DenialReason.TOOL_INACTIVE,
                null
            );
            return CompletableFuture.failedFuture(
                new ProtocolException(
                    ErrorCode.UNSUPPORTED,
                    "Nested scheduled Tool is not currently allowed."
                )
            );
        }

        ExecutionPolicy.Decision nestedDecision =
            executionPolicy.evaluate(
                arguments.tool(),
                request.toolsAllowed(),
                request.mode()
            );
        if (!nestedDecision.allowed()) {
            support.logToolDenied(
                request,
                arguments.tool(),
                nestedDecision.reason(),
                null
            );
            return CompletableFuture.failedFuture(
                new ProtocolException(
                    ErrorCode.UNSUPPORTED,
                    "Nested scheduled Tool is not currently allowed."
                )
            );
        }

        Instant sentAt = support.now();
        Instant deadline = earlier(
            budget.deadlineAt(),
            sentAt.plus(TOOL_TIMEOUT)
        );
        UUID toolCallId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        return support.requirePreAudit(
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
                deadline,
                sentAt.plus(PRE_AUDIT_TIMEOUT)
            )
        ).thenCompose(ignored -> {
            support.assertRunning();
            support.assertSession(
                request.requesterUuid(),
                request.sessionId()
            );

            schedulingPolicy.validateRegistration(arguments);

            ExecutionPolicy.Decision scheduleDecision =
                executionPolicy.evaluate(
                    call.tool(),
                    request.toolsAllowed(),
                    request.mode()
                );
            if (!scheduleDecision.allowed()) {
                support.logToolDenied(
                    request,
                    call.tool(),
                    ExecutionPolicy.DenialReason.POLICY_CHANGED,
                    scheduleDecision.reason()
                );
                return CompletableFuture.failedFuture(
                    new ProtocolException(
                        ErrorCode.UNSUPPORTED,
                        "Schedule was denied by the current policy."
                    )
                );
            }

            if (!support.activeTools().contains(arguments.tool())) {
                support.logToolDenied(
                    request,
                    arguments.tool(),
                    ExecutionPolicy.DenialReason.TOOL_INACTIVE,
                    null
                );
                return CompletableFuture.failedFuture(
                    new ProtocolException(
                        ErrorCode.UNSUPPORTED,
                        "Schedule was denied by the current policy."
                    )
                );
            }

            ExecutionPolicy.Decision currentNestedDecision =
                executionPolicy.evaluate(
                    arguments.tool(),
                    request.toolsAllowed(),
                    request.mode()
                );
            if (!currentNestedDecision.allowed()) {
                support.logToolDenied(
                    request,
                    arguments.tool(),
                    ExecutionPolicy.DenialReason.POLICY_CHANGED,
                    currentNestedDecision.reason()
                );
                return CompletableFuture.failedFuture(
                    new ProtocolException(
                        ErrorCode.UNSUPPORTED,
                        "Schedule was denied by the current policy."
                    )
                );
            }

            support.logToolStarted(
                request,
                call.tool(),
                toolCallId,
                actionId
            );

            ToolResult result;
            try {
                ScheduledActionService.Snapshot snapshot =
                    scheduledActions.schedule(
                        request.requesterUuid(),
                        arguments,
                        (scheduleId, runIndex) ->
                            runScheduledAction(
                                request,
                                routing,
                                arguments,
                                scheduleId,
                                runIndex
                            )
                    );
                log.info(
                    JarvisEvents.SCHEDULE_CREATED,
                    JarvisFields.of(
                        "scheduleId", snapshot.scheduleId(),
                        "requesterUuid", request.requesterUuid(),
                        "tool", arguments.tool().wireName(),
                        "delaySeconds", arguments.delaySeconds(),
                        "intervalSeconds", arguments.intervalSeconds(),
                        "durationSeconds", arguments.durationSeconds()
                    )
                );
                result = ToolResult.ok(
                    new ScheduledActionData(
                        snapshot.scheduleId(),
                        arguments.tool(),
                        arguments.delaySeconds(),
                        arguments.intervalSeconds(),
                        arguments.durationSeconds(),
                        snapshot.firstRunAt(),
                        arguments.durationSeconds() == null
                            ? null
                            : snapshot.expiresAt(),
                        true
                    ),
                    support.now(),
                    "JarvisScheduler"
                );
            } catch (RuntimeException failure) {
                result = ToolResult.error(
                    ErrorCode.BUSY,
                    "Scheduled action could not be registered.",
                    false,
                    support.now(),
                    "JarvisScheduler"
                );
            }

            return recordControlResult(
                request,
                budget,
                routing,
                call,
                toolCallId,
                actionId,
                result,
                sentAt
            );
        });
    }

    private CompletionStage<Void> cancelScheduledAction(
        EmbeddedBrain.ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        LunaStep.ToolCall call
    ) {
        CancelScheduledActionArguments arguments =
            (CancelScheduledActionArguments) call.arguments();

        Instant sentAt = support.now();
        Instant deadline = earlier(
            budget.deadlineAt(),
            sentAt.plus(TOOL_TIMEOUT)
        );
        UUID toolCallId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        return support.requirePreAudit(
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
                deadline,
                sentAt.plus(PRE_AUDIT_TIMEOUT)
            )
        ).thenCompose(ignored -> {
            support.assertRunning();
            support.assertSession(
                request.requesterUuid(),
                request.sessionId()
            );

            ExecutionPolicy.Decision cancelDecision =
                executionPolicy.evaluate(
                    call.tool(),
                    request.toolsAllowed(),
                    request.mode()
                );
            if (!cancelDecision.allowed()) {
                support.logToolDenied(
                    request,
                    call.tool(),
                    ExecutionPolicy.DenialReason.POLICY_CHANGED,
                    cancelDecision.reason()
                );
                return CompletableFuture.failedFuture(
                    new ProtocolException(
                        ErrorCode.UNSUPPORTED,
                        "Schedule cancellation is no longer allowed."
                    )
                );
            }

            support.logToolStarted(
                request,
                call.tool(),
                toolCallId,
                actionId
            );

            boolean cancelled = scheduledActions.cancel(
                request.requesterUuid(),
                arguments.scheduleId()
            );
            if (cancelled) {
                log.info(
                    JarvisEvents.SCHEDULE_CANCELLED,
                    JarvisFields.of(
                        "scheduleId", arguments.scheduleId(),
                        "requesterUuid", request.requesterUuid()
                    )
                );
            }

            ToolResult result = cancelled
                ? ToolResult.ok(
                    new CancelScheduledActionData(
                        arguments.scheduleId(),
                        true
                    ),
                    support.now(),
                    "JarvisScheduler"
                )
                : ToolResult.error(
                    ErrorCode.NOT_FOUND,
                    "Pending scheduled action was not found for this requester.",
                    false,
                    support.now(),
                    "JarvisScheduler"
                );

            return recordControlResult(
                request,
                budget,
                routing,
                call,
                toolCallId,
                actionId,
                result,
                sentAt
            );
        });
    }

    private CompletionStage<Void> recordControlResult(
        EmbeddedBrain.ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        LunaStep.ToolCall call,
        UUID toolCallId,
        UUID actionId,
        ToolResult result,
        Instant startedAt
    ) {
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
                support.now().plus(POST_AUDIT_TIMEOUT)
            )
        ).thenApply(ignored -> {
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
    }

    private CompletionStage<Boolean> runScheduledAction(
        EmbeddedBrain.ChatRequest origin,
        DeterministicRoutePolicy.RoutingDecision routing,
        ScheduleActionArguments schedule,
        UUID scheduleId,
        int runIndex
    ) {
        String blockedReason =
            scheduledAbortReason(origin, schedule);
        if (blockedReason != null) {
            logScheduleAborted(
                scheduleId,
                origin.requesterUuid(),
                schedule.tool(),
                runIndex,
                blockedReason
            );
            return CompletableFuture.completedFuture(false);
        }

        LunaStep.ToolCall nested =
            new LunaStep.ToolCall(
                schedule.tool(),
                schedule.arguments()
            );
        Instant sentAt = support.now();
        Instant deadline = sentAt.plus(TOOL_TIMEOUT);
        UUID toolCallId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        log.debug(
            JarvisEvents.SCHEDULE_RUN_STARTED,
            JarvisFields.of(
                "scheduleId", scheduleId,
                "runIndex", runIndex,
                "requestId", origin.requestId(),
                "requesterUuid", origin.requesterUuid(),
                "tool", schedule.tool().wireName(),
                "toolCallId", toolCallId,
                "actionId", actionId
            )
        );

        CompletionStage<Void> preAudit =
            support.requirePreAudit(
                support.auditEvent(
                    origin,
                    routing,
                    nested,
                    toolCallId,
                    actionId,
                    "SCHEDULED_PRE_EXECUTION_" + runIndex,
                    "JarvisScheduler",
                    0L
                ),
                earlier(
                    deadline,
                    sentAt.plus(PRE_AUDIT_TIMEOUT)
                )
            );

        return preAudit.thenCompose(ignored -> {
            String currentBlockedReason =
                scheduledAbortReason(origin, schedule);
            if (currentBlockedReason != null) {
                logScheduleAborted(
                    scheduleId,
                    origin.requesterUuid(),
                    schedule.tool(),
                    runIndex,
                    currentBlockedReason
                );
                return CompletableFuture.completedFuture(false);
            }

            support.logToolStarted(
                origin,
                schedule.tool(),
                toolCallId,
                actionId
            );

            CommonRuntime.ToolInvocation invocation =
                new CommonRuntime.ToolInvocation(
                    sentAt,
                    deadline,
                    origin.requesterUuid(),
                    origin.requestId(),
                    origin.sessionId(),
                    toolCallId,
                    actionId,
                    schedule.tool(),
                    schedule.arguments()
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
                        origin,
                        schedule.tool(),
                        toolCallId,
                        actionId,
                        result,
                        latency
                    );
                    log.debug(
                        JarvisEvents.SCHEDULE_RUN_COMPLETED,
                        JarvisFields.of(
                            "scheduleId", scheduleId,
                            "runIndex", runIndex,
                            "requestId", origin.requestId(),
                            "tool", schedule.tool().wireName(),
                            "toolCallId", toolCallId,
                            "actionId", actionId,
                            "outcome", result.status(),
                            "errorCode", support.toolErrorCode(result),
                            "latencyMs", latency
                        )
                    );

                    String abortReason =
                        scheduleResultAbortReason(result);
                    if (abortReason != null) {
                        logScheduleAborted(
                            scheduleId,
                            origin.requesterUuid(),
                            schedule.tool(),
                            runIndex,
                            abortReason
                        );
                    }

                    return support.tryPostAudit(
                        support.auditEvent(
                            origin,
                            routing,
                            nested,
                            toolCallId,
                            actionId,
                            "SCHEDULED_" + result.status().name(),
                            result.source(),
                            latency
                        ),
                        support.now().plus(
                            POST_AUDIT_TIMEOUT
                        )
                    ).thenApply(
                        postIgnored -> abortReason == null
                    );
                });
        }).handle((keepGoing, failure) -> {
            if (failure != null) {
                logScheduleAborted(
                    scheduleId,
                    origin.requesterUuid(),
                    schedule.tool(),
                    runIndex,
                    scheduleFailureReason(failure)
                );
                return false;
            }
            return Boolean.TRUE.equals(keepGoing);
        });
    }

    private String scheduledAbortReason(
        EmbeddedBrain.ChatRequest origin,
        ScheduleActionArguments schedule
    ) {
        if (support.stopped()) {
            return "SERVER_STOPPING";
        }
        if (!schedulingPolicy.stillAllowed(schedule)) {
            return "SCHEDULING_DISABLED";
        }
        if (!support.activeTools().contains(schedule.tool())) {
            return "TOOL_INACTIVE";
        }

        ExecutionPolicy.Decision decision =
            executionPolicy.evaluate(
                schedule.tool(),
                origin.toolsAllowed(),
                "SCHEDULED"
            );
        if (!decision.allowed()) {
            return decision.reason()
                    == ExecutionPolicy.DenialReason.NO_REQUESTER_AUTHORITY
                ? "AUTHORITY_REVOKED"
                : "EXECUTION_POLICY_REVOKED";
        }
        return null;
    }

    private String scheduleResultAbortReason(
        ToolResult result
    ) {
        if (
            result.status()
                == io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus.OK
                || result.status()
                    == io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus.EMPTY
        ) {
            return null;
        }

        ErrorCode code = support.toolErrorCode(result);
        if (code == null) {
            return "TOOL_FAILED";
        }
        return switch (code) {
            case UNAUTHORIZED -> "AUTHORITY_REVOKED";
            case TIMEOUT -> "TIMEOUT";
            case OUTCOME_UNKNOWN -> "OUTCOME_UNKNOWN";
            case CANCELLED -> "CANCELLED";
            default -> "TOOL_FAILED";
        };
    }

    private String scheduleFailureReason(
        Throwable failure
    ) {
        Throwable cause = unwrap(failure);
        if (cause instanceof ProtocolException protocol) {
            return switch (protocol.code()) {
                case UNAUTHORIZED -> "AUTHORITY_REVOKED";
                case TIMEOUT -> "TIMEOUT";
                case OUTCOME_UNKNOWN -> "OUTCOME_UNKNOWN";
                case CANCELLED -> "CANCELLED";
                case INTERNAL -> "AUDIT_FAILURE";
                default -> "TOOL_FAILED";
            };
        }
        return "TOOL_FAILED";
    }

    private void logScheduleAborted(
        UUID scheduleId,
        UUID requesterUuid,
        ToolName tool,
        int runIndex,
        String reason
    ) {
        log.warn(
            JarvisEvents.SCHEDULE_ABORTED,
            JarvisFields.of(
                "scheduleId", scheduleId,
                "requesterUuid", requesterUuid,
                "tool", tool.wireName(),
                "runIndex", runIndex,
                "reason", reason
            )
        );
    }
}
