package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaStep;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaTurnInput;
import io.github.kardane.jarvisminecraft.common.brain.ai.ReasoningLevel;
import io.github.kardane.jarvisminecraft.common.logging.JarvisEvents;
import io.github.kardane.jarvisminecraft.common.logging.JarvisFields;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.prompt.PromptContentSnapshot;
import io.github.kardane.jarvisminecraft.common.runtime.ExecutionPolicy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.elapsedMillis;
import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.failureCode;
import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.unwrap;
import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.withDeadline;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

final class ModelConversationLoop {
    private static final String LUNA_FAILURE_TEXT =
        "현재 GPT-6 Luna 응답을 완료할 수 없습니다. 서버 상태나 작업 성공 여부를 추측하지 않았습니다.";

    private final Supplier<List<Capability>> capabilities;
    private final ConversationHistoryStore history;
    private final LunaClient luna;
    private final ExecutionPolicy executionPolicy;
    private final ToolExecutionCoordinator tools;
    private final BrainRuntimeGuard guard;
    private final Clock clock;
    private final JarvisLog log;

    ModelConversationLoop(
        Supplier<List<Capability>> capabilities,
        ConversationHistoryStore history,
        LunaClient luna,
        ExecutionPolicy executionPolicy,
        ToolExecutionCoordinator tools,
        BrainRuntimeGuard guard,
        Clock clock,
        JarvisLog log
    ) {
        this.capabilities = Objects.requireNonNull(
            capabilities,
            "capabilities"
        );
        this.history = Objects.requireNonNull(history, "history");
        this.luna = Objects.requireNonNull(luna, "luna");
        this.executionPolicy = Objects.requireNonNull(
            executionPolicy,
            "executionPolicy"
        );
        this.tools = Objects.requireNonNull(tools, "tools");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.log = Objects.requireNonNull(log, "log");
    }

    CompletionStage<EmbeddedBrain.Reply> run(
        EmbeddedBrain.ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        ReasoningLevel reasoningLevel
    ) {
        return run(
            request,
            budget,
            routing,
            reasoningLevel,
            PromptContentSnapshot.empty()
        );
    }

    CompletionStage<EmbeddedBrain.Reply> run(
        EmbeddedBrain.ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        ReasoningLevel reasoningLevel,
        PromptContentSnapshot promptContent
    ) {
        return run(
            request,
            budget,
            routing,
            reasoningLevel,
            promptContent,
            ConversationMemorySnapshot.empty()
        );
    }

    CompletionStage<EmbeddedBrain.Reply> run(
        EmbeddedBrain.ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        ReasoningLevel reasoningLevel,
        PromptContentSnapshot promptContent,
        ConversationMemorySnapshot conversationMemory
    ) {
        return run(
            request,
            budget,
            routing,
            reasoningLevel,
            promptContent,
            conversationMemory,
            LunaStep.Usage.zero()
        );
    }

    private CompletionStage<EmbeddedBrain.Reply> run(
        EmbeddedBrain.ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        ReasoningLevel reasoningLevel,
        PromptContentSnapshot promptContent,
        ConversationMemorySnapshot conversationMemory,
        LunaStep.Usage accumulatedUsage
    ) {
        Objects.requireNonNull(
            promptContent,
            "promptContent"
        );
        Objects.requireNonNull(
            conversationMemory,
            "conversationMemory"
        );
        Objects.requireNonNull(
            accumulatedUsage,
            "accumulatedUsage"
        );
        try {
            guard.assertRunning();
            guard.assertSession(
                request.requesterUuid(),
                request.sessionId()
            );
            budget.consumeModelRound(clock.instant());
            int round = RequestBudget.MAX_MODEL_ROUNDS
                - budget.remainingModelRounds()
                - 1;

            DeterministicRoutePolicy.RoutingDecision effectiveRouting =
                currentRouting(request, routing);

            LunaTurnInput input = new LunaTurnInput(
                request.requestId(),
                request.requesterName(),
                history.history(
                    request.requesterUuid(),
                    request.sessionId()
                ),
                capabilities.get(),
                effectiveRouting.availableTools(),
                budget.remainingToolCalls(),
                budget.remainingModelRounds(),
                reasoningLevel,
                promptContent,
                conversationMemory,
                budget.deadlineAt()
            );

            Instant lunaStarted = clock.instant();
            CompletionStage<LunaStep> model = withDeadline(
                luna.next(input, effectiveRouting),
                budget.deadlineAt(),
                ErrorCode.TIMEOUT,
                "Model response exceeded the request deadline.",
                clock
            );

            return model.handle(ModelOutcome::new)
                .thenCompose(outcome ->
                    handleOutcome(
                        request,
                        budget,
                        routing,
                        reasoningLevel,
                        promptContent,
                        conversationMemory,
                        accumulatedUsage,
                        effectiveRouting,
                        round,
                        lunaStarted,
                        outcome
                    )
                );
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<EmbeddedBrain.Reply> handleOutcome(
        EmbeddedBrain.ChatRequest request,
        RequestBudget budget,
        DeterministicRoutePolicy.RoutingDecision routing,
        ReasoningLevel reasoningLevel,
        PromptContentSnapshot promptContent,
        ConversationMemorySnapshot conversationMemory,
        LunaStep.Usage accumulatedUsage,
        DeterministicRoutePolicy.RoutingDecision effectiveRouting,
        int round,
        Instant lunaStarted,
        ModelOutcome outcome
    ) {
        guard.assertRunning();
        guard.assertSession(
            request.requesterUuid(),
            request.sessionId()
        );

        if (outcome.failure() != null) {
            Throwable cause = unwrap(outcome.failure());
            String errorMessage = cause.getMessage() != null ? cause.getMessage() : cause.toString();
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
                    "errorClass", cause.getClass().getSimpleName(),
                    "errorDetail", errorMessage,
                    "latencyMs", elapsedMillis(
                        lunaStarted,
                        clock
                    )
                )
            );
            luna.clear(request.requestId());
            history.append(
                request.requesterUuid(),
                request.sessionId(),
                new ConversationEntry.AssistantMessage(
                    LUNA_FAILURE_TEXT,
                    request.requestId(),
                    clock.instant(),
                    request.mode()
                )
            );
            return CompletableFuture.completedFuture(
                new EmbeddedBrain.Reply(
                    LUNA_FAILURE_TEXT,
                    LunaStep.SessionState.CONTINUE,
                    accumulatedUsage
                )
            );
        }

        LunaStep step = outcome.step();
        LunaStep.Usage updatedUsage =
            accumulatedUsage.plus(step.usage());
        if (step instanceof LunaStep.Final finalStep) {
            log.debug(
                JarvisEvents.LUNA_ROUND_COMPLETED,
                JarvisFields.of(
                    "requestId", request.requestId(),
                    "round", round,
                    "kind", "FINAL",
                    "toolCallCount", 0,
                    "latencyMs", elapsedMillis(
                        lunaStarted,
                        clock
                    )
                )
            );
            validateFinal(finalStep);
            history.append(
                request.requesterUuid(),
                request.sessionId(),
                new ConversationEntry.AssistantMessage(
                    finalStep.text(),
                    request.requestId(),
                    clock.instant(),
                    request.mode()
                )
            );
            return CompletableFuture.completedFuture(
                new EmbeddedBrain.Reply(
                    finalStep.text(),
                    finalStep.sessionState(),
                    updatedUsage
                )
            );
        }

        LunaStep.Tools toolStep = (LunaStep.Tools) step;
        log.debug(
            JarvisEvents.LUNA_ROUND_COMPLETED,
            JarvisFields.of(
                "requestId", request.requestId(),
                "round", round,
                "kind", "TOOL_CALLS",
                "toolCallCount", toolStep.calls().size(),
                "latencyMs", elapsedMillis(
                    lunaStarted,
                    clock
                )
            )
        );

        budget.consumeToolCalls(
            toolStep.calls().size(),
            clock.instant()
        );
        validateRoutedTools(
            effectiveRouting.availableTools(),
            toolStep.calls()
        );

        return tools.executeSequentially(
            request,
            budget,
            effectiveRouting,
            toolStep.calls()
        ).thenCompose(
            ignored -> run(
                request,
                budget,
                routing,
                reasoningLevel,
                promptContent,
                conversationMemory,
                updatedUsage
            )
        );
    }

    private DeterministicRoutePolicy.RoutingDecision currentRouting(
        EmbeddedBrain.ChatRequest request,
        DeterministicRoutePolicy.RoutingDecision routing
    ) {
        Set<ToolName> available = executionPolicy.filter(
            routing.availableTools(),
            request.toolsAllowed(),
            request.mode()
        );
        long mutationCount = available.stream()
            .filter(ToolName::stateChanging)
            .count();

        log.debug(
            JarvisEvents.TOOL_EXPOSURE_RESOLVED,
            JarvisFields.of(
                "requestId", request.requestId(),
                "executionMode", executionPolicy.currentMode(),
                "candidateCount", routing.availableTools().size(),
                "allowedCount", available.size(),
                "mutationCount", mutationCount
            )
        );

        return new DeterministicRoutePolicy.RoutingDecision(
            routing.category(),
            routing.fallbackReason(),
            available
        );
    }

    private void validateRoutedTools(
        Set<ToolName> availableTools,
        List<LunaStep.ToolCall> calls
    ) {
        for (LunaStep.ToolCall call : calls) {
            if (!availableTools.contains(call.tool())) {
                throw new ProtocolException(
                    ErrorCode.INVALID_ARGUMENT,
                    "Model requested a Tool outside the routed allowlist."
                );
            }
        }
    }

    private void validateFinal(LunaStep.Final step) {
        if (
            step.text().isBlank()
                || step.text().length() > 12_000
        ) {
            throw new ProtocolException(
                ErrorCode.INVALID_ARGUMENT,
                "Model final text is outside protocol limits."
            );
        }
    }

    private record ModelOutcome(
        LunaStep step,
        Throwable failure
    ) {
    }
}
