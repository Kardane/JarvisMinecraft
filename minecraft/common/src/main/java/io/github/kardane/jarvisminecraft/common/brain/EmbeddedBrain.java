package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.audit.AuditArgumentSummaries;
import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.JdkJevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassification;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevInput;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaStep;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaTurnInput;
import io.github.kardane.jarvisminecraft.common.brain.ai.ReasoningLevel;
import io.github.kardane.jarvisminecraft.common.brain.ai.ReasoningPolicy;
import io.github.kardane.jarvisminecraft.common.chat.AmbientChatMessage;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.logging.JarvisEvents;
import io.github.kardane.jarvisminecraft.common.logging.JarvisFields;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.logging.NoOpJarvisLog;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.brain.Capability;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class EmbeddedBrain {
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PRE_AUDIT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration POST_AUDIT_TIMEOUT = Duration.ofSeconds(1);
    private static final String LUNA_FAILURE_TEXT =
        "현재 GPT-6 Luna 응답을 완료할 수 없습니다. 서버 상태나 작업 성공 여부를 추측하지 않았습니다.";

    private final String serverId;
    private final List<Capability> capabilities;
    private final ChatSessionManager sessions;
    private final ConversationHistoryStore history;
    private final AiRequestScheduler scheduler;
    private final JevClassifier jev;
    private final DeterministicRoutePolicy routePolicy;
    private final LunaClient luna;
    private final ReasoningPolicy reasoningPolicy;
    private final ExecutionPolicy executionPolicy;
    private final SchedulingPolicy schedulingPolicy;
    private final ScheduledActionService scheduledActions;
    private final AuditSink audit;
    private final CommonRuntime.ExecutionRuntime toolRuntime;
    private final Clock clock;
    private final JarvisLog log;
    private final Map<SessionKey, Set<UUID>> activeRequestIds = new HashMap<>();
    private volatile boolean stopped;

    public EmbeddedBrain(
        String serverId,
        List<Capability> capabilities,
        ChatSessionManager sessions,
        ConversationHistoryStore history,
        AiRequestScheduler scheduler,
        JevClassifier jev,
        DeterministicRoutePolicy routePolicy,
        LunaClient luna,
        AuditSink audit,
        CommonRuntime.ExecutionRuntime toolRuntime,
        Clock clock
    ) {
        this(
            serverId,
            capabilities,
            sessions,
            history,
            scheduler,
            jev,
            routePolicy,
            luna,
            ReasoningPolicy.defaults(),
            ExecutionPolicy.defaults(),
            audit,
            toolRuntime,
            clock
        );
    }

    public EmbeddedBrain(
        String serverId,
        List<Capability> capabilities,
        ChatSessionManager sessions,
        ConversationHistoryStore history,
        AiRequestScheduler scheduler,
        JevClassifier jev,
        DeterministicRoutePolicy routePolicy,
        LunaClient luna,
        ReasoningPolicy reasoningPolicy,
        ExecutionPolicy executionPolicy,
        AuditSink audit,
        CommonRuntime.ExecutionRuntime toolRuntime,
        Clock clock
    ) {
        this(
            serverId,
            capabilities,
            sessions,
            history,
            scheduler,
            jev,
            routePolicy,
            luna,
            reasoningPolicy,
            executionPolicy,
            SchedulingPolicy.defaults(),
            new ScheduledActionService(clock),
            audit,
            toolRuntime,
            clock
        );
    }

    public EmbeddedBrain(
        String serverId,
        List<Capability> capabilities,
        ChatSessionManager sessions,
        ConversationHistoryStore history,
        AiRequestScheduler scheduler,
        JevClassifier jev,
        DeterministicRoutePolicy routePolicy,
        LunaClient luna,
        ReasoningPolicy reasoningPolicy,
        ExecutionPolicy executionPolicy,
        SchedulingPolicy schedulingPolicy,
        ScheduledActionService scheduledActions,
        AuditSink audit,
        CommonRuntime.ExecutionRuntime toolRuntime,
        Clock clock
    ) {
        this(
            serverId,
            capabilities,
            sessions,
            history,
            scheduler,
            jev,
            routePolicy,
            luna,
            reasoningPolicy,
            executionPolicy,
            schedulingPolicy,
            scheduledActions,
            audit,
            toolRuntime,
            clock,
            NoOpJarvisLog.INSTANCE
        );
    }

    public EmbeddedBrain(
        String serverId,
        List<Capability> capabilities,
        ChatSessionManager sessions,
        ConversationHistoryStore history,
        AiRequestScheduler scheduler,
        JevClassifier jev,
        DeterministicRoutePolicy routePolicy,
        LunaClient luna,
        ReasoningPolicy reasoningPolicy,
        ExecutionPolicy executionPolicy,
        SchedulingPolicy schedulingPolicy,
        ScheduledActionService scheduledActions,
        AuditSink audit,
        CommonRuntime.ExecutionRuntime toolRuntime,
        Clock clock,
        JarvisLog log
    ) {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId must not be blank.");
        }
        this.serverId = serverId;
        this.capabilities = List.copyOf(
            Objects.requireNonNull(capabilities, "capabilities")
        );
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.history = Objects.requireNonNull(history, "history");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.jev = Objects.requireNonNull(jev, "jev");
        this.routePolicy = Objects.requireNonNull(routePolicy, "routePolicy");
        this.luna = Objects.requireNonNull(luna, "luna");
        this.reasoningPolicy = Objects.requireNonNull(
            reasoningPolicy,
            "reasoningPolicy"
        );
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
        this.clock = Objects.requireNonNull(clock, "clock");
        this.log = Objects.requireNonNull(log, "log");
    }

    public CompletionStage<JevClassification> classifyProactive(
        List<AmbientChatMessage> context,
        Instant deadlineAt
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (stopped) {
            return CompletableFuture.failedFuture(
                new ProtocolException(
                    ErrorCode.CANCELLED,
                    "Embedded Brain is stopped."
                )
            );
        }
        return jev.classify(
            JevInput.fromAmbient(
                context,
                currentCapabilities()
            ),
            deadlineAt
        );
    }

    public CompletionStage<Reply> submit(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        assertRunning();
        assertSession(request.requesterUuid(), request.sessionId());

        track(request.requesterUuid(), request.sessionId(), request.requestId());
        CompletionStage<Reply> stage;
        try {
            stage = scheduler.submit(
                request.requesterUuid(),
                request.sessionId(),
                () -> processScheduled(request)
            );
        } catch (RuntimeException failure) {
            untrack(
                request.requesterUuid(),
                request.sessionId(),
                request.requestId()
            );
            return CompletableFuture.failedFuture(failure);
        }

        return stage.whenComplete((ignored, failure) -> {
            luna.clear(request.requestId());
            untrack(
                request.requesterUuid(),
                request.sessionId(),
                request.requestId()
            );
        });
    }

    public void cancelSession(UUID requesterUuid, UUID sessionId) {
        scheduler.cancelSession(requesterUuid, sessionId);
        history.clearSession(requesterUuid, sessionId);
        clearTracked(new SessionKey(requesterUuid, sessionId));
    }

    public void cancelActor(UUID requesterUuid) {
        scheduler.cancelActor(requesterUuid);
        scheduledActions.cancelActor(requesterUuid);
        history.clearActor(requesterUuid);

        List<SessionKey> keys;
        synchronized (activeRequestIds) {
            keys = activeRequestIds.keySet().stream()
                .filter(key -> key.requesterUuid().equals(requesterUuid))
                .toList();
        }
        keys.forEach(this::clearTracked);
    }

    public void stop() {
        stopped = true;
        scheduledActions.close();
        scheduler.shutdown();
        Set<UUID> requestIds = new HashSet<>();
        synchronized (activeRequestIds) {
            for (Set<UUID> ids : activeRequestIds.values()) {
                requestIds.addAll(ids);
            }
            activeRequestIds.clear();
        }
        requestIds.forEach(luna::clear);
    }

    private CompletionStage<Reply> processScheduled(ChatRequest request) {
        try {
            assertRunning();
            assertSession(request.requesterUuid(), request.sessionId());
            RequestBudget budget =
                new RequestBudget(request.deadlineAt(), request.receivedAt());
            budget.assertLive(clock.instant());

            history.append(
                request.requesterUuid(),
                request.sessionId(),
                new ConversationEntry.UserMessage(
                    request.text(),
                    request.requestId(),
                    clock.instant()
                )
            );

            EnumSet<ToolName> candidates =
                EnumSet.noneOf(ToolName.class);
            candidates.addAll(toolRuntime.activeTools());
            candidates.add(ToolName.SCHEDULE_ACTION);
            candidates.add(
                ToolName.CANCEL_SCHEDULED_ACTION
            );
            Set<ToolName> activeTools = executionPolicy.filter(
                candidates,
                request.toolsAllowed(),
                request.mode()
            );
            JevInput input = JevInput.fromConversation(
                history.history(
                    request.requesterUuid(),
                    request.sessionId()
                ),
                currentCapabilities(),
                request.mode()
            );

            Instant jevStarted = clock.instant();
            Instant jevDeadline = earlier(
                budget.deadlineAt(),
                jevStarted.plus(JdkJevClassifier.MAX_TIMEOUT)
            );
            return withDeadline(
                jev.classify(input, jevDeadline),
                jevDeadline,
                ErrorCode.TIMEOUT,
                "Jev classification timed out."
            ).handle((classification, failure) -> {
                    long latency = elapsedMillis(jevStarted);
                    if (
                        failure != null
                            || classification == null
                            || !JdkJevClassifier.MODEL.equals(
                                classification.model()
                            )
                    ) {
                        String errorCode = failureCode(
                            failure,
                            "JEV_FAILED",
                            "JEV_TIMEOUT"
                        );
                        if (
                            classification != null
                                && !JdkJevClassifier.MODEL.equals(
                                    classification.model()
                                )
                        ) {
                            errorCode = "JEV_INVALID_OUTPUT";
                        }
                        log.warn(
                            JarvisEvents.JEV_FAILED,
                            JarvisFields.of(
                                "requestId", request.requestId(),
                                "errorCode", errorCode,
                                "latencyMs", latency
                            )
                        );
                        DeterministicRoutePolicy.RoutingDecision fallback =
                            routePolicy.errorFallback(activeTools);
                        ReasoningLevel reasoning =
                            reasoningPolicy.fallback();
                        log.warn(
                            JarvisEvents.JEV_FALLBACK,
                            JarvisFields.of(
                                "requestId", request.requestId(),
                                "reason", fallback.fallbackReason(),
                                "fallbackRoute", fallback.category(),
                                "fallbackReasoning", reasoning,
                                "latencyMs", latency
                            )
                        );
                        logPlanning(request, fallback, reasoning);
                        return new PlanningDecision(
                            fallback,
                            reasoning
                        );
                    }

                    log.debug(
                        JarvisEvents.JEV_COMPLETED,
                        JarvisFields.of(
                            "requestId", request.requestId(),
                            "route", classification.category(),
                            "reasoning", classification.reasoning(),
                            "engagement", classification.engagement(),
                            "confidence", classification.confidence(),
                            "latencyMs", latency
                        )
                    );
                    DeterministicRoutePolicy.RoutingDecision routing =
                        routePolicy.route(
                            classification,
                            activeTools
                        );
                    ReasoningLevel reasoning =
                        reasoningPolicy.resolve(classification);
                    if (routing.fallbackActive()) {
                        log.warn(
                            JarvisEvents.JEV_FALLBACK,
                            JarvisFields.of(
                                "requestId", request.requestId(),
                                "reason", routing.fallbackReason(),
                                "fallbackRoute", routing.category(),
                                "fallbackReasoning", reasoning,
                                "latencyMs", latency
                            )
                        );
                    }
                    logPlanning(request, routing, reasoning);
                    return new PlanningDecision(
                        routing,
                        reasoning
                    );
                })
                .thenCompose(
                    plan -> modelLoop(
                        request,
                        budget,
                        plan.routing(),
                        plan.reasoningLevel()
                    )
                );
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<Reply> modelLoop(
        ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        ReasoningLevel reasoningLevel
    ) {
        try {
            assertRunning();
            assertSession(request.requesterUuid(), request.sessionId());
            budget.consumeModelRound(clock.instant());
            int round = RequestBudget.MAX_MODEL_ROUNDS
                - budget.remainingModelRounds()
                - 1;

            DeterministicRoutePolicy.RoutingDecision effectiveRouting =
                currentRouting(request, routing);

            LunaTurnInput input = new LunaTurnInput(
                request.requestId(),
                request.requesterName(),
                history.history(request.requesterUuid(), request.sessionId()),
                currentCapabilities(),
                effectiveRouting.availableTools(),
                budget.remainingToolCalls(),
                budget.remainingModelRounds(),
                reasoningLevel,
                budget.deadlineAt()
            );

            Instant lunaStarted = clock.instant();
            CompletionStage<LunaStep> model = withDeadline(
                luna.next(input, effectiveRouting),
                budget.deadlineAt(),
                ErrorCode.TIMEOUT,
                "Model response exceeded the request deadline."
            );

            return model.handle((step, failure) -> new ModelOutcome(step, failure))
                .thenCompose(outcome -> {
                    assertRunning();
                    assertSession(
                        request.requesterUuid(),
                        request.sessionId()
                    );

                    if (outcome.failure() != null) {
                        log.warn(
                            JarvisEvents.LUNA_FAILED,
                            JarvisFields.of(
                                "requestId", request.requestId(),
                                "round", round,
                                "errorCode", failureCode(
                                    outcome.failure(),
                                    "LUNA_FAILED",
                                    "LUNA_TIMEOUT"
                                ),
                                "latencyMs", elapsedMillis(lunaStarted)
                            )
                        );
                        luna.clear(request.requestId());
                        history.append(
                            request.requesterUuid(),
                            request.sessionId(),
                            new ConversationEntry.AssistantMessage(
                                LUNA_FAILURE_TEXT,
                                request.requestId(),
                                clock.instant()
                            )
                        );
                        return CompletableFuture.completedFuture(
                            new Reply(
                                LUNA_FAILURE_TEXT,
                                LunaStep.SessionState.CONTINUE
                            )
                        );
                    }

                    LunaStep step = outcome.step();
                    if (step instanceof LunaStep.Final finalStep) {
                        log.debug(
                            JarvisEvents.LUNA_ROUND_COMPLETED,
                            JarvisFields.of(
                                "requestId", request.requestId(),
                                "round", round,
                                "kind", "FINAL",
                                "toolCallCount", 0,
                                "latencyMs", elapsedMillis(lunaStarted)
                            )
                        );
                        validateFinal(finalStep);
                        history.append(
                            request.requesterUuid(),
                            request.sessionId(),
                            new ConversationEntry.AssistantMessage(
                                finalStep.text(),
                                request.requestId(),
                                clock.instant()
                            )
                        );
                        return CompletableFuture.completedFuture(
                            new Reply(
                                finalStep.text(),
                                finalStep.sessionState()
                            )
                        );
                    }

                    LunaStep.Tools tools = (LunaStep.Tools) step;
                    log.debug(
                        JarvisEvents.LUNA_ROUND_COMPLETED,
                        JarvisFields.of(
                            "requestId", request.requestId(),
                            "round", round,
                            "kind", "TOOL_CALLS",
                            "toolCallCount", tools.calls().size(),
                            "latencyMs", elapsedMillis(lunaStarted)
                        )
                    );
                    budget.consumeToolCalls(
                        tools.calls().size(),
                        clock.instant()
                    );

                    for (LunaStep.ToolCall call : tools.calls()) {
                        if (
                            !effectiveRouting.availableTools().contains(
                                call.tool()
                            )
                        ) {
                            throw new ProtocolException(
                                ErrorCode.INVALID_ARGUMENT,
                                "Model requested a Tool outside the routed allowlist."
                            );
                        }
                    }

                    return executeToolsSequentially(
                        request,
                        budget,
                        effectiveRouting,
                        tools.calls()
                    ).thenCompose(
                        ignored -> modelLoop(
                            request,
                            budget,
                            routing,
                            reasoningLevel
                        )
                    );
                });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private List<Capability> currentCapabilities() {
        if (!schedulingPolicy.schedulingEnabled()) {
            return capabilities;
        }
        for (Capability capability : capabilities) {
            if ("action.schedule".equals(capability.name())) {
                return capabilities;
            }
        }
        List<Capability> expanded =
            new ArrayList<>(capabilities);
        expanded.add(
            new Capability(
                "action.schedule",
                "JarvisCommon",
                "phase7"
            )
        );
        return List.copyOf(expanded);
    }

    private DeterministicRoutePolicy.RoutingDecision currentRouting(
        ChatRequest request,
        DeterministicRoutePolicy.RoutingDecision routing
    ) {
        Set<ToolName> available = executionPolicy.filter(
            routing.availableTools(),
            request.toolsAllowed(),
            request.mode()
        );
        return new DeterministicRoutePolicy.RoutingDecision(
            routing.category(),
            routing.fallbackReason(),
            available
        );
    }

    private CompletionStage<Void> executeToolsSequentially(
        ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        List<LunaStep.ToolCall> calls
    ) {
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (LunaStep.ToolCall call : calls) {
            chain = chain.thenCompose(
                ignored -> executeTool(request, budget, routing, call)
            );
        }
        return chain;
    }

    private CompletionStage<Void> executeTool(
        ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        LunaStep.ToolCall call
    ) {
        assertRunning();
        assertSession(request.requesterUuid(), request.sessionId());
        budget.assertLive(clock.instant());

        if (
            !executionPolicy.allows(
                call.tool(),
                request.toolsAllowed(),
                request.mode()
            )
        ) {
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
        if (
            call.tool()
                == ToolName.CANCEL_SCHEDULED_ACTION
        ) {
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
                earlier(toolDeadline, sentAt.plus(PRE_AUDIT_TIMEOUT))
            )
            : CompletableFuture.completedFuture(null);

        return preAudit.thenCompose(ignored -> {
            assertRunning();
            assertSession(request.requesterUuid(), request.sessionId());

            if (
                !executionPolicy.allows(
                    call.tool(),
                    request.toolsAllowed(),
                    request.mode()
                )
            ) {
                return CompletableFuture.failedFuture(
                    new ProtocolException(
                        ErrorCode.UNSUPPORTED,
                        "Tool was denied by the current execution policy before execution."
                    )
                );
            }

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
                        Duration.between(startedAt, clock.instant()).toMillis()
                    );

                    CompletionStage<Void> postAudit = tryPostAudit(
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
                    );

                    return postAudit.thenApply(postIgnored -> {
                        assertSession(
                            request.requesterUuid(),
                            request.sessionId()
                        );
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
                        return null;
                    });
                });
        });
    }

    private CompletionStage<Void> scheduleAction(
        ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        LunaStep.ToolCall call
    ) {
        ScheduleActionArguments arguments =
            (ScheduleActionArguments) call.arguments();
        schedulingPolicy.validateRegistration(arguments);

        if (
            !toolRuntime.activeTools().contains(
                arguments.tool()
            )
                || !executionPolicy.allows(
                    arguments.tool(),
                    request.toolsAllowed(),
                    request.mode()
                )
        ) {
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
            assertRunning();
            assertSession(
                request.requesterUuid(),
                request.sessionId()
            );
            schedulingPolicy.validateRegistration(
                arguments
            );
            if (
                !executionPolicy.allows(
                    call.tool(),
                    request.toolsAllowed(),
                    request.mode()
                )
                    || !toolRuntime.activeTools().contains(
                        arguments.tool()
                    )
                    || !executionPolicy.allows(
                        arguments.tool(),
                        request.toolsAllowed(),
                        request.mode()
                    )
            ) {
                return CompletableFuture.failedFuture(
                    new ProtocolException(
                        ErrorCode.UNSUPPORTED,
                        "Schedule was denied by the current policy."
                    )
                );
            }

            ToolResult result;
            try {
                ScheduledActionService.Snapshot snapshot =
                    scheduledActions.schedule(
                        request.requesterUuid(),
                        arguments,
                        runIndex -> runScheduledAction(
                            request,
                            routing,
                            arguments,
                            runIndex
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
        ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        LunaStep.ToolCall call
    ) {
        CancelScheduledActionArguments arguments =
            (CancelScheduledActionArguments)
                call.arguments();

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
            assertRunning();
            assertSession(
                request.requesterUuid(),
                request.sessionId()
            );
            if (
                !executionPolicy.allows(
                    call.tool(),
                    request.toolsAllowed(),
                    request.mode()
                )
            ) {
                return CompletableFuture.failedFuture(
                    new ProtocolException(
                        ErrorCode.UNSUPPORTED,
                        "Schedule cancellation is no longer allowed."
                    )
                );
            }

            boolean cancelled = scheduledActions.cancel(
                request.requesterUuid(),
                arguments.scheduleId()
            );
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
        ChatRequest request,
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
                clock.instant().plus(
                    POST_AUDIT_TIMEOUT
                )
            )
        ).thenApply(ignored -> {
            assertSession(
                request.requesterUuid(),
                request.sessionId()
            );
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
            return null;
        });
    }

    private CompletionStage<Boolean> runScheduledAction(
        ChatRequest origin,
        DeterministicRoutePolicy.RoutingDecision routing,
        ScheduleActionArguments schedule,
        int runIndex
    ) {
        if (
            stopped
                || !schedulingPolicy.stillAllowed(
                    schedule
                )
                || !toolRuntime.activeTools().contains(
                    schedule.tool()
                )
                || !executionPolicy.allows(
                    schedule.tool(),
                    origin.toolsAllowed(),
                    "SCHEDULED"
                )
        ) {
            return CompletableFuture.completedFuture(
                false
            );
        }

        LunaStep.ToolCall nested =
            new LunaStep.ToolCall(
                schedule.tool(),
                schedule.arguments()
            );
        Instant sentAt = clock.instant();
        Instant deadline =
            sentAt.plus(TOOL_TIMEOUT);
        UUID toolCallId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        CompletionStage<Void> preAudit =
            requirePreAudit(
                auditEvent(
                    origin,
                    routing,
                    nested,
                    toolCallId,
                    actionId,
                    "SCHEDULED_PRE_EXECUTION_"
                        + runIndex,
                    "JarvisScheduler",
                    0L
                ),
                earlier(
                    deadline,
                    sentAt.plus(
                        PRE_AUDIT_TIMEOUT
                    )
                )
            );

        return preAudit.thenCompose(ignored -> {
            if (
                stopped
                    || !schedulingPolicy.stillAllowed(
                        schedule
                    )
                    || !toolRuntime.activeTools().contains(
                        schedule.tool()
                    )
                    || !executionPolicy.allows(
                        schedule.tool(),
                        origin.toolsAllowed(),
                        "SCHEDULED"
                    )
            ) {
                return CompletableFuture.completedFuture(
                    false
                );
            }

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
                    return tryPostAudit(
                        auditEvent(
                            origin,
                            routing,
                            nested,
                            toolCallId,
                            actionId,
                            "SCHEDULED_"
                                + result.status().name(),
                            result.source(),
                            latency
                        ),
                        clock.instant().plus(
                            POST_AUDIT_TIMEOUT
                        )
                    ).thenApply(postIgnored ->
                        result.status()
                            == io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus.OK
                            || result.status()
                            == io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus.EMPTY
                    );
                });
        }).handle(
            (keepGoing, failure) ->
                failure == null
                    && Boolean.TRUE.equals(
                        keepGoing
                    )
        );
    }

    private CompletionStage<Void> requirePreAudit(
        AuditSink.AuditEvent event,
        Instant deadlineAt
    ) {
        return withDeadline(
            audit.record(event),
            deadlineAt,
            ErrorCode.INTERNAL,
            "Pre-execution audit could not be confirmed."
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
            "Post-execution audit timed out."
        ).handle((ignored, failure) -> null);
    }

    private AuditSink.AuditEvent auditEvent(
        ChatRequest request,
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
            AuditArgumentSummaries.summarize(call.arguments()),
            outcome,
            source,
            latencyMillis,
            luna.modelId(),
            routing.fallbackReason() == null
                ? null
                : routing.fallbackReason().name()
        );
    }

    private void logPlanning(
        ChatRequest request,
        DeterministicRoutePolicy.RoutingDecision routing,
        ReasoningLevel reasoning
    ) {
        log.debug(
            JarvisEvents.ROUTING_RESOLVED,
            JarvisFields.of(
                "requestId", request.requestId(),
                "route", routing.category(),
                "fallback", routing.fallbackActive(),
                "availableTools", routing.availableTools().size()
            )
        );
        log.debug(
            JarvisEvents.REASONING_RESOLVED,
            JarvisFields.of(
                "requestId", request.requestId(),
                "reasoning", reasoning
            )
        );
    }

    private long elapsedMillis(Instant startedAt) {
        return Math.max(
            0L,
            Duration.between(startedAt, clock.instant()).toMillis()
        );
    }

    private String failureCode(
        Throwable failure,
        String fallback,
        String timeout
    ) {
        if (failure == null) {
            return fallback;
        }
        Throwable cause = unwrap(failure);
        if (
            cause instanceof ProtocolException protocol
                && protocol.code() == ErrorCode.TIMEOUT
        ) {
            return timeout;
        }
        return fallback;
    }

    private void validateFinal(LunaStep.Final step) {
        if (step.text().isBlank() || step.text().length() > 12_000) {
            throw new ProtocolException(
                ErrorCode.INVALID_ARGUMENT,
                "Model final text is outside protocol limits."
            );
        }
    }

    private void assertRunning() {
        if (stopped) {
            throw new ProtocolException(
                ErrorCode.CANCELLED,
                "Embedded Brain is stopped."
            );
        }
    }

    private void assertSession(UUID requesterUuid, UUID sessionId) {
        if (!sessions.isActive(requesterUuid, sessionId)) {
            throw new ProtocolException(
                ErrorCode.CANCELLED,
                "Conversation session is no longer active."
            );
        }
    }

    private <T> CompletionStage<T> withDeadline(
        CompletionStage<T> stage,
        Instant deadlineAt,
        ErrorCode code,
        String message
    ) {
        long delay = Math.max(
            0L,
            Duration.between(clock.instant(), deadlineAt).toMillis()
        );
        if (delay == 0L) {
            return CompletableFuture.failedFuture(
                new ProtocolException(code, message)
            );
        }

        CompletableFuture<T> output = new CompletableFuture<>();
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                output.complete(value);
            } else {
                output.completeExceptionally(unwrap(failure));
            }
        });
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
            .execute(
                () -> output.completeExceptionally(
                    new ProtocolException(code, message)
                )
            );
        return output;
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (
            current instanceof CompletionException
                && current.getCause() != null
        ) {
            current = current.getCause();
        }
        return current;
    }

    private Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private void track(UUID requesterUuid, UUID sessionId, UUID requestId) {
        synchronized (activeRequestIds) {
            activeRequestIds
                .computeIfAbsent(
                    new SessionKey(requesterUuid, sessionId),
                    ignored -> new HashSet<>()
                )
                .add(requestId);
        }
    }

    private void untrack(UUID requesterUuid, UUID sessionId, UUID requestId) {
        synchronized (activeRequestIds) {
            SessionKey key = new SessionKey(requesterUuid, sessionId);
            Set<UUID> ids = activeRequestIds.get(key);
            if (ids == null) {
                return;
            }
            ids.remove(requestId);
            if (ids.isEmpty()) {
                activeRequestIds.remove(key);
            }
        }
    }

    private void clearTracked(SessionKey key) {
        Set<UUID> ids;
        synchronized (activeRequestIds) {
            ids = activeRequestIds.remove(key);
        }
        if (ids != null) {
            ids.forEach(luna::clear);
        }
    }

    public record ChatRequest(
        UUID requestId,
        UUID requesterUuid,
        String requesterName,
        UUID sessionId,
        String mode,
        String text,
        Instant receivedAt,
        Instant deadlineAt,
        boolean toolsAllowed
    ) {
        public ChatRequest(
            UUID requestId,
            UUID requesterUuid,
            String requesterName,
            UUID sessionId,
            String mode,
            String text,
            Instant receivedAt,
            Instant deadlineAt
        ) {
            this(
                requestId,
                requesterUuid,
                requesterName,
                sessionId,
                mode,
                text,
                receivedAt,
                deadlineAt,
                true
            );
        }

        public ChatRequest {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(requesterUuid, "requesterUuid");
            Objects.requireNonNull(requesterName, "requesterName");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(receivedAt, "receivedAt");
            Objects.requireNonNull(deadlineAt, "deadlineAt");
            if (!deadlineAt.isAfter(receivedAt)) {
                throw new IllegalArgumentException(
                    "deadlineAt must be after receivedAt."
                );
            }
        }
    }

    public record Reply(
        String text,
        LunaStep.SessionState sessionState
    ) {
        public Reply {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(sessionState, "sessionState");
        }
    }

    private record SessionKey(UUID requesterUuid, UUID sessionId) {
    }

    private record PlanningDecision(
        DeterministicRoutePolicy.RoutingDecision routing,
        ReasoningLevel reasoningLevel
    ) {
        private PlanningDecision {
            Objects.requireNonNull(routing, "routing");
            Objects.requireNonNull(
                reasoningLevel,
                "reasoningLevel"
            );
        }
    }

    private record ModelOutcome(LunaStep step, Throwable failure) {
    }
}
