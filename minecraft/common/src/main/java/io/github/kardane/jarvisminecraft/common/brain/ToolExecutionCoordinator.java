package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.audit.AuditArgumentSummaries;
import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
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
import io.github.kardane.jarvisminecraft.common.runtime.AuditSink;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ExecutionPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ScheduledActionService;
import io.github.kardane.jarvisminecraft.common.runtime.SchedulingPolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.earlier;
import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.unwrap;
import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.withDeadline;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

final class ToolExecutionCoordinator {
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PRE_AUDIT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration POST_AUDIT_TIMEOUT = Duration.ofSeconds(1);

    private final String serverId;
    private final ConversationHistoryStore history;
    private final LunaClient luna;
    private final ExecutionPolicy executionPolicy;
    private final SchedulingPolicy schedulingPolicy;
    private final ScheduledActionService scheduledActions;
    private final AuditSink audit;
    private final CommonRuntime.ExecutionRuntime toolRuntime;
    private final BrainRuntimeGuard guard;
    private final Clock clock;
    private final JarvisLog log;

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
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.history = Objects.requireNonNull(history, "history");
        this.luna = Objects.requireNonNull(luna, "luna");
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
        this.audit = Objects.requireNonNull(audit, "audit");
        this.toolRuntime = Objects.requireNonNull(toolRuntime, "toolRuntime");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.log = Objects.requireNonNull(log, "log");
    }

    Set<ToolName> activeTools() {
        return toolRuntime.activeTools();
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
        guard.assertRunning();
        guard.assertSession(
            request.requesterUuid(),
            request.sessionId()
        );
        budget.assertLive(clock.instant());

        ExecutionPolicy.Decision initialDecision =
            executionPolicy.evaluate(
                call.tool(),
                request.toolsAllowed(),
                request.mode()
            );
        if (!initialDecision.allowed()) {
            logToolDenied(
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

        if (call.tool() == ToolName.SCHEDULE_ACTION) {
            return scheduleAction(
                request,
                budget,
                routing,
                call
            );
        }
        if (call.tool() == ToolName.CANCEL_SCHEDULED_ACTION) {
            return cancelScheduledAction(
                request,
                budget,
                routing,
                call
            );
        }

        Instant sentAt = clock.instant();
        Instant toolDeadline = earlier(
            budget.deadlineAt(),
            sentAt.plus(TOOL_TIMEOUT)
        );
        UUID toolCallId = UUID.randomUUID();
        UUID actionId = call.tool().stateChanging()
            ? UUID.randomUUID()
            : null;

        AuditSink.AuditEvent preEvent = auditEvent(
            request,
            routing,
            call,
            toolCallId,
            actionId,
            "PRE_EXECUTION",
            "BrainPolicy",
            0L
        );

        CompletionStage<Void> preAudit = call.tool().stateChanging()
            ? requirePreAudit(
                preEvent,
                earlier(
                    toolDeadline,
                    sentAt.plus(PRE_AUDIT_TIMEOUT)
                )
            )
            : CompletableFuture.completedFuture(null);

        return preAudit.thenCompose(ignored -> {
            guard.assertRunning();
            guard.assertSession(
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
                logToolDenied(
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

            logToolStarted(
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

            Instant startedAt = clock.instant();
            return toolRuntime.execute(invocation)
                .thenCompose(result -> {
                    long latency = Math.max(
                        0L,
                        Duration.between(
                            startedAt,
                            clock.instant()
                        ).toMillis()
                    );

                    logToolCompleted(
                        request,
                        call.tool(),
                        toolCallId,
                        actionId,
                        result,
                        latency
                    );

                    CompletionStage<Void> postAudit =
                        tryPostAudit(
                            auditEvent(
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
                                clock.instant().plus(
                                    POST_AUDIT_TIMEOUT
                                )
                            )
                        );

                    return postAudit.thenApply(
                        postIgnored -> {
                            guard.assertSession(
                                request.requesterUuid(),
                                request.sessionId()
                            );
                            appendToolResult(
                                request,
                                call,
                                toolCallId,
                                result
                            );
                            return null;
                        }
                    );
                });
        });
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

        if (!toolRuntime.activeTools().contains(arguments.tool())) {
            logToolDenied(
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
            logToolDenied(
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

        Instant sentAt = clock.instant();
        Instant deadline = earlier(
            budget.deadlineAt(),
            sentAt.plus(TOOL_TIMEOUT)
        );
        UUID toolCallId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        return requirePreAudit(
            auditEvent(
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
            guard.assertRunning();
            guard.assertSession(
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
                logToolDenied(
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

            if (!toolRuntime.activeTools().contains(arguments.tool())) {
                logToolDenied(
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
                logToolDenied(
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

            logToolStarted(
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
                    clock.instant(),
                    "JarvisScheduler"
                );
            } catch (RuntimeException failure) {
                result = ToolResult.error(
                    ErrorCode.BUSY,
                    "Scheduled action could not be registered.",
                    false,
                    clock.instant(),
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

        Instant sentAt = clock.instant();
        Instant deadline = earlier(
            budget.deadlineAt(),
            sentAt.plus(TOOL_TIMEOUT)
        );
        UUID toolCallId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        return requirePreAudit(
            auditEvent(
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
            guard.assertRunning();
            guard.assertSession(
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
                logToolDenied(
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

            logToolStarted(
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
                    clock.instant(),
                    "JarvisScheduler"
                )
                : ToolResult.error(
                    ErrorCode.NOT_FOUND,
                    "Pending scheduled action was not found for this requester.",
                    false,
                    clock.instant(),
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
                clock.instant()
            ).toMillis()
        );

        logToolCompleted(
            request,
            call.tool(),
            toolCallId,
            actionId,
            result,
            latency
        );

        return tryPostAudit(
            auditEvent(
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
                clock.instant().plus(POST_AUDIT_TIMEOUT)
            )
        ).thenApply(ignored -> {
            guard.assertSession(
                request.requesterUuid(),
                request.sessionId()
            );
            appendToolResult(
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
        Instant sentAt = clock.instant();
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
            requirePreAudit(
                auditEvent(
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

            logToolStarted(
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
            Instant startedAt = clock.instant();

            return toolRuntime.execute(invocation)
                .thenCompose(result -> {
                    long latency = Math.max(
                        0L,
                        Duration.between(
                            startedAt,
                            clock.instant()
                        ).toMillis()
                    );

                    logToolCompleted(
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
                            "errorCode", toolErrorCode(result),
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

                    return tryPostAudit(
                        auditEvent(
                            origin,
                            routing,
                            nested,
                            toolCallId,
                            actionId,
                            "SCHEDULED_" + result.status().name(),
                            result.source(),
                            latency
                        ),
                        clock.instant().plus(POST_AUDIT_TIMEOUT)
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

    private CompletionStage<Void> requirePreAudit(
        AuditSink.AuditEvent event,
        Instant deadlineAt
    ) {
        return withDeadline(
            audit.record(event),
            deadlineAt,
            ErrorCode.INTERNAL,
            "Pre-execution audit could not be confirmed.",
            clock
        ).thenCompose(recorded -> {
            if (Boolean.TRUE.equals(recorded)) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.failedFuture(
                new ProtocolException(
                    ErrorCode.INTERNAL,
                    "State-changing Tool was refused because audit is unavailable."
                )
            );
        });
    }

    private CompletionStage<Void> tryPostAudit(
        AuditSink.AuditEvent event,
        Instant deadlineAt
    ) {
        return withDeadline(
            audit.record(event),
            deadlineAt,
            ErrorCode.INTERNAL,
            "Post-execution audit timed out.",
            clock
        ).handle((ignored, failure) -> null);
    }

    private AuditSink.AuditEvent auditEvent(
        EmbeddedBrain.ChatRequest request,
        DeterministicRoutePolicy.RoutingDecision routing,
        LunaStep.ToolCall call,
        UUID toolCallId,
        UUID actionId,
        String outcome,
        String source,
        long latencyMillis
    ) {
        return new AuditSink.AuditEvent(
            clock.instant(),
            serverId,
            request.requesterUuid(),
            request.requestId(),
            toolCallId,
            actionId,
            call.tool(),
            call.tool().risk(),
            AuditArgumentSummaries.summarize(
                call.arguments()
            ),
            outcome,
            source,
            latencyMillis,
            luna.modelId(),
            routing.fallbackReason() == null
                ? null
                : routing.fallbackReason().name()
        );
    }

    private void appendToolResult(
        EmbeddedBrain.ChatRequest request,
        LunaStep.ToolCall call,
        UUID toolCallId,
        ToolResult result
    ) {
        history.append(
            request.requesterUuid(),
            request.sessionId(),
            new ConversationEntry.ToolMessage(
                call.tool(),
                toolCallId,
                result,
                request.requestId(),
                clock.instant()
            )
        );
    }

    private void logToolDenied(
        EmbeddedBrain.ChatRequest request,
        ToolName tool,
        ExecutionPolicy.DenialReason reason,
        ExecutionPolicy.DenialReason policyReason
    ) {
        Map<String, Object> fields = JarvisFields.of(
            "requestId", request.requestId(),
            "requesterUuid", request.requesterUuid(),
            "origin", request.mode(),
            "tool", tool.wireName(),
            "reason", reason,
            "policyReason", policyReason,
            "executionMode", executionPolicy.currentMode()
        );
        if (tool.stateChanging()) {
            log.warn(JarvisEvents.TOOL_DENIED, fields);
        } else {
            log.debug(JarvisEvents.TOOL_DENIED, fields);
        }
    }

    private void logToolStarted(
        EmbeddedBrain.ChatRequest request,
        ToolName tool,
        UUID toolCallId,
        UUID actionId
    ) {
        log.debug(
            JarvisEvents.TOOL_STARTED,
            JarvisFields.of(
                "requestId", request.requestId(),
                "requesterUuid", request.requesterUuid(),
                "tool", tool.wireName(),
                "toolCallId", toolCallId,
                "actionId", actionId,
                "risk", tool.risk()
            )
        );
    }

    private void logToolCompleted(
        EmbeddedBrain.ChatRequest request,
        ToolName tool,
        UUID toolCallId,
        UUID actionId,
        ToolResult result,
        long latency
    ) {
        ErrorCode errorCode = toolErrorCode(result);
        log.info(
            JarvisEvents.TOOL_COMPLETED,
            JarvisFields.of(
                "requestId", request.requestId(),
                "requesterUuid", request.requesterUuid(),
                "tool", tool.wireName(),
                "toolCallId", toolCallId,
                "actionId", actionId,
                "risk", tool.risk(),
                "outcome", result.status(),
                "source", result.source(),
                "errorCode", errorCode,
                "latencyMs", latency
            )
        );
        if (errorCode == ErrorCode.OUTCOME_UNKNOWN) {
            log.warn(
                JarvisEvents.TOOL_OUTCOME_UNKNOWN,
                JarvisFields.of(
                    "requestId", request.requestId(),
                    "requesterUuid", request.requesterUuid(),
                    "tool", tool.wireName(),
                    "toolCallId", toolCallId,
                    "actionId", actionId
                )
            );
        }
    }

    private ErrorCode toolErrorCode(ToolResult result) {
        return result.error() == null
            ? null
            : result.error().code();
    }

    private String scheduledAbortReason(
        EmbeddedBrain.ChatRequest origin,
        ScheduleActionArguments schedule
    ) {
        if (guard.stopped()) {
            return "SERVER_STOPPING";
        }
        if (!schedulingPolicy.stillAllowed(schedule)) {
            return "SCHEDULING_DISABLED";
        }
        if (!toolRuntime.activeTools().contains(schedule.tool())) {
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

    private String scheduleResultAbortReason(ToolResult result) {
        if (
            result.status()
                == io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus.OK
                || result.status()
                    == io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus.EMPTY
        ) {
            return null;
        }

        ErrorCode code = toolErrorCode(result);
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

    private String scheduleFailureReason(Throwable failure) {
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
