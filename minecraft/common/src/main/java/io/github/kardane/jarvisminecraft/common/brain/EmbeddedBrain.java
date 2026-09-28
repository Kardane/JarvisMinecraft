package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassification;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevInput;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaStep;
import io.github.kardane.jarvisminecraft.common.brain.ai.ReasoningPolicy;
import io.github.kardane.jarvisminecraft.common.chat.AmbientChatMessage;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.logging.JarvisEvents;
import io.github.kardane.jarvisminecraft.common.logging.JarvisFields;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.logging.NoOpJarvisLog;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.prompt.PromptContentSnapshot;
import io.github.kardane.jarvisminecraft.common.runtime.AuditSink;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ExecutionPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ScheduledActionService;
import io.github.kardane.jarvisminecraft.common.runtime.SchedulingPolicy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

public final class EmbeddedBrain {
    private final List<Capability> capabilities;
    private final ConversationHistoryStore history;
    private final AiRequestScheduler scheduler;
    private final JevClassifier jev;
    private final LunaClient luna;
    private final Clock clock;
    private final BrainRuntimeGuard guard;
    private final RequestPlanner planner;
    private final ToolExecutionCoordinator toolExecution;
    private final ModelConversationLoop modelLoop;
    private final Supplier<PromptContentSnapshot> promptContent;
    private final ConversationMemoryStore conversationMemory;
    private final Map<SessionKey, Set<UUID>> activeRequestIds =
        new HashMap<>();

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
            log,
            PromptContentSnapshot::empty
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
        JarvisLog log,
        Supplier<PromptContentSnapshot> promptContent
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
            log,
            promptContent,
            ConversationMemoryStore.disabled()
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
        JarvisLog log,
        Supplier<PromptContentSnapshot> promptContent,
        ConversationMemoryStore conversationMemory
    ) {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException(
                "serverId must not be blank."
            );
        }

        this.capabilities = List.copyOf(
            Objects.requireNonNull(
                capabilities,
                "capabilities"
            )
        );
        Objects.requireNonNull(sessions, "sessions");
        this.history = Objects.requireNonNull(
            history,
            "history"
        );
        this.scheduler = Objects.requireNonNull(
            scheduler,
            "scheduler"
        );
        this.jev = Objects.requireNonNull(jev, "jev");
        Objects.requireNonNull(
            routePolicy,
            "routePolicy"
        );
        this.luna = Objects.requireNonNull(luna, "luna");
        Objects.requireNonNull(
            reasoningPolicy,
            "reasoningPolicy"
        );
        Objects.requireNonNull(
            executionPolicy,
            "executionPolicy"
        );
        Objects.requireNonNull(
            schedulingPolicy,
            "schedulingPolicy"
        );
        Objects.requireNonNull(
            scheduledActions,
            "scheduledActions"
        );
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(
            toolRuntime,
            "toolRuntime"
        );
        this.clock = Objects.requireNonNull(
            clock,
            "clock"
        );
        Objects.requireNonNull(log, "log");
        this.promptContent = Objects.requireNonNull(
            promptContent,
            "promptContent"
        );
        this.conversationMemory = Objects.requireNonNull(
            conversationMemory,
            "conversationMemory"
        );

        this.guard = new BrainRuntimeGuard(
            sessions,
            () -> stopped
        );
        this.planner = new RequestPlanner(
            jev,
            routePolicy,
            reasoningPolicy,
            executionPolicy,
            clock,
            log
        );
        this.toolExecution =
            new ToolExecutionCoordinator(
                serverId,
                history,
                luna,
                executionPolicy,
                schedulingPolicy,
                scheduledActions,
                audit,
                toolRuntime,
                guard,
                clock,
                log
            );
        this.modelLoop = new ModelConversationLoop(
            this::currentCapabilities,
            history,
            luna,
            executionPolicy,
            toolExecution,
            guard,
            clock,
            log
        );
    }

    public CompletionStage<JevClassification> classifyFollowUp(
        UUID requesterUuid,
        UUID sessionId,
        String latestMessage,
        Instant deadlineAt
    ) {
        Objects.requireNonNull(
            requesterUuid,
            "requesterUuid"
        );
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(
            latestMessage,
            "latestMessage"
        );
        Objects.requireNonNull(deadlineAt, "deadlineAt");

        guard.assertRunning();
        guard.assertSession(
            requesterUuid,
            sessionId
        );

        return jev.classify(
            JevInput.fromFollowUpCandidate(
                history.history(
                    requesterUuid,
                    sessionId
                ),
                latestMessage,
                currentCapabilities()
            ),
            deadlineAt
        );
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

    public CompletionStage<Reply> submit(
        ChatRequest request
    ) {
        Objects.requireNonNull(request, "request");
        guard.assertRunning();
        guard.assertSession(
            request.requesterUuid(),
            request.sessionId()
        );

        track(
            request.requesterUuid(),
            request.sessionId(),
            request.requestId()
        );

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

    public void cancelSession(
        UUID requesterUuid,
        UUID sessionId
    ) {
        scheduler.cancelSession(
            requesterUuid,
            sessionId
        );
        history.clearSession(
            requesterUuid,
            sessionId
        );
        clearTracked(
            new SessionKey(
                requesterUuid,
                sessionId
            )
        );
    }

    public void cancelActor(UUID requesterUuid) {
        scheduler.cancelActor(requesterUuid);
        toolExecution.cancelActor(requesterUuid);
        history.clearActor(requesterUuid);

        List<SessionKey> keys;
        synchronized (activeRequestIds) {
            keys = activeRequestIds
                .keySet()
                .stream()
                .filter(
                    key -> key.requesterUuid()
                        .equals(requesterUuid)
                )
                .toList();
        }
        keys.forEach(this::clearTracked);
    }

    public AiRequestScheduler.Snapshot schedulerSnapshot() {
        return scheduler.snapshot();
    }

    public void stop() {
        stopped = true;
        toolExecution.close();
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

    private CompletionStage<Reply> processScheduled(
        ChatRequest request
    ) {
        try {
            guard.assertRunning();
            guard.assertSession(
                request.requesterUuid(),
                request.sessionId()
            );

            RequestBudget budget = new RequestBudget(
                request.deadlineAt(),
                request.receivedAt()
            );
            budget.assertLive(clock.instant());

            PromptContentSnapshot requestPromptContent =
                Objects.requireNonNull(
                    promptContent.get(),
                    "promptContent snapshot"
                );

            CompletionStage<ConversationMemorySnapshot>
                memoryStage = retrieveMemory(
                    request,
                    budget
                );

            return memoryStage.thenCompose(
                requestMemory -> {
                    guard.assertRunning();
                    guard.assertSession(
                        request.requesterUuid(),
                        request.sessionId()
                    );
                    budget.assertLive(clock.instant());

                    history.append(
                        request.requesterUuid(),
                        request.sessionId(),
                        new ConversationEntry.UserMessage(
                            request.text(),
                            request.requestId(),
                            clock.instant(),
                            request.mode()
                        )
                    );

                    return planner.plan(
                        request,
                        budget,
                        history.history(
                            request.requesterUuid(),
                            request.sessionId()
                        ),
                        currentCapabilities(),
                        toolExecution.activeTools()
                    ).thenCompose(
                        plan -> modelLoop.run(
                            request,
                            budget,
                            plan.routing(),
                            plan.reasoningLevel(),
                            requestPromptContent,
                            requestMemory
                        )
                    );
                }
            );
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<ConversationMemorySnapshot>
        retrieveMemory(
            ChatRequest request,
            RequestBudget budget
        ) {
        Instant deadline = BrainAsync.earlier(
            budget.deadlineAt(),
            clock.instant().plusMillis(350L)
        );

        CompletionStage<ConversationMemorySnapshot> stage;
        try {
            stage = conversationMemory.retrieve(
                request.requesterUuid(),
                request.sessionId(),
                request.requestId(),
                request.text()
            );
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(
                ConversationMemorySnapshot.empty()
            );
        }

        return BrainAsync.withDeadline(
            stage,
            deadline,
            ErrorCode.TIMEOUT,
            "Conversation memory retrieval timed out.",
            clock
        ).exceptionally(failure -> {
            log.debug(
                JarvisEvents.CONVERSATION_MEMORY_FAILED,
                JarvisFields.of(
                    "requestId", request.requestId(),
                    "reason", BrainAsync.unwrap(failure)
                        .getClass()
                        .getSimpleName()
                )
            );
            return ConversationMemorySnapshot.empty();
        });
    }

    private List<Capability> currentCapabilities() {
        if (!toolExecution.schedulingEnabled()) {
            return capabilities;
        }
        for (Capability capability : capabilities) {
            if (
                "action.schedule".equals(
                    capability.name()
                )
            ) {
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

    private void track(
        UUID requesterUuid,
        UUID sessionId,
        UUID requestId
    ) {
        synchronized (activeRequestIds) {
            activeRequestIds
                .computeIfAbsent(
                    new SessionKey(
                        requesterUuid,
                        sessionId
                    ),
                    ignored -> new HashSet<>()
                )
                .add(requestId);
        }
    }

    private void untrack(
        UUID requesterUuid,
        UUID sessionId,
        UUID requestId
    ) {
        synchronized (activeRequestIds) {
            SessionKey key =
                new SessionKey(
                    requesterUuid,
                    sessionId
                );
            Set<UUID> ids =
                activeRequestIds.get(key);
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
            Objects.requireNonNull(
                requestId,
                "requestId"
            );
            Objects.requireNonNull(
                requesterUuid,
                "requesterUuid"
            );
            Objects.requireNonNull(
                requesterName,
                "requesterName"
            );
            Objects.requireNonNull(
                sessionId,
                "sessionId"
            );
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(
                receivedAt,
                "receivedAt"
            );
            Objects.requireNonNull(
                deadlineAt,
                "deadlineAt"
            );
            if (!deadlineAt.isAfter(receivedAt)) {
                throw new IllegalArgumentException(
                    "deadlineAt must be after receivedAt."
                );
            }
        }
    }

    public record Reply(
        String text,
        LunaStep.SessionState sessionState,
        LunaStep.Usage usage
    ) {
        public Reply(
            String text,
            LunaStep.SessionState sessionState
        ) {
            this(
                text,
                sessionState,
                LunaStep.Usage.unavailable()
            );
        }

        public Reply {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(
                sessionState,
                "sessionState"
            );
            Objects.requireNonNull(usage, "usage");
        }
    }

    private record SessionKey(
        UUID requesterUuid,
        UUID sessionId
    ) {
    }
}
