package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.audit.AuditArgumentSummaries;
import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.JdkJevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevInput;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaStep;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaTurnInput;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolMessage;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.AuditSink;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final List<ProtocolMessage.Capability> capabilities;
    private final ChatSessionManager sessions;
    private final ConversationHistoryStore history;
    private final AiRequestScheduler scheduler;
    private final JevClassifier jev;
    private final DeterministicRoutePolicy routePolicy;
    private final LunaClient luna;
    private final AuditSink audit;
    private final CommonRuntime.ExecutionRuntime toolRuntime;
    private final Clock clock;
    private final Map<SessionKey, Set<UUID>> activeRequestIds = new HashMap<>();
    private volatile boolean stopped;

    public EmbeddedBrain(
        String serverId,
        List<ProtocolMessage.Capability> capabilities,
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
        this.audit = Objects.requireNonNull(audit, "audit");
        this.toolRuntime = Objects.requireNonNull(toolRuntime, "toolRuntime");
        this.clock = Objects.requireNonNull(clock, "clock");
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

            Set<ToolName> activeTools = toolRuntime.activeTools();
            JevInput input = JevInput.fromConversation(
                history.history(request.requesterUuid(), request.sessionId()),
                capabilities
            );

            Instant jevDeadline = earlier(
                budget.deadlineAt(),
                clock.instant().plus(JdkJevClassifier.MAX_TIMEOUT)
            );
            return withDeadline(
                jev.classify(input, jevDeadline),
                jevDeadline,
                ErrorCode.TIMEOUT,
                "Jev classification timed out."
            ).handle((classification, failure) -> {
                    if (
                        failure != null
                            || classification == null
                            || !JdkJevClassifier.MODEL.equals(
                                classification.model()
                            )
                    ) {
                        return routePolicy.errorFallback(activeTools);
                    }
                    return routePolicy.route(classification, activeTools);
                })
                .thenCompose(
                    routing -> modelLoop(request, budget, routing)
                );
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<Reply> modelLoop(
        ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing
    ) {
        try {
            assertRunning();
            assertSession(request.requesterUuid(), request.sessionId());
            budget.consumeModelRound(clock.instant());

            LunaTurnInput input = new LunaTurnInput(
                request.requestId(),
                request.requesterName(),
                history.history(request.requesterUuid(), request.sessionId()),
                capabilities,
                routing.availableTools(),
                budget.remainingToolCalls(),
                budget.remainingModelRounds(),
                budget.deadlineAt()
            );

            CompletionStage<LunaStep> model = withDeadline(
                luna.next(input, routing),
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
                    budget.consumeToolCalls(
                        tools.calls().size(),
                        clock.instant()
                    );

                    for (LunaStep.ToolCall call : tools.calls()) {
                        if (!routing.availableTools().contains(call.tool())) {
                            throw new ProtocolException(
                                ErrorCode.INVALID_ARGUMENT,
                                "Model requested a Tool outside the routed allowlist."
                            );
                        }
                    }

                    return executeToolsSequentially(
                        request,
                        budget,
                        routing,
                        tools.calls()
                    ).thenCompose(
                        ignored -> modelLoop(request, budget, routing)
                    );
                });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
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
        Instant deadlineAt
    ) {
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

    private record ModelOutcome(LunaStep step, Throwable failure) {
    }
}
