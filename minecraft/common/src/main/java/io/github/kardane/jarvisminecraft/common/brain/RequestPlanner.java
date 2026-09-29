package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.JdkJevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassification;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevInput;
import io.github.kardane.jarvisminecraft.common.brain.ai.ReasoningLevel;
import io.github.kardane.jarvisminecraft.common.brain.ai.ReasoningPolicy;
import io.github.kardane.jarvisminecraft.common.logging.JarvisEvents;
import io.github.kardane.jarvisminecraft.common.logging.JarvisFields;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.runtime.ExecutionPolicy;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.earlier;
import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.elapsedMillis;
import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.failureCode;
import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.withDeadline;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

final class RequestPlanner {
    private final JevClassifier jev;
    private final DeterministicRoutePolicy routePolicy;
    private final ReasoningPolicy reasoningPolicy;
    private final ExecutionPolicy executionPolicy;
    private final Clock clock;
    private final JarvisLog log;

    RequestPlanner(
        JevClassifier jev,
        DeterministicRoutePolicy routePolicy,
        ReasoningPolicy reasoningPolicy,
        ExecutionPolicy executionPolicy,
        Clock clock,
        JarvisLog log
    ) {
        this.jev = Objects.requireNonNull(jev, "jev");
        this.routePolicy = Objects.requireNonNull(routePolicy, "routePolicy");
        this.reasoningPolicy = Objects.requireNonNull(
            reasoningPolicy,
            "reasoningPolicy"
        );
        this.executionPolicy = Objects.requireNonNull(
            executionPolicy,
            "executionPolicy"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
        this.log = Objects.requireNonNull(log, "log");
    }

    CompletionStage<PlanningDecision> plan(
        EmbeddedBrain.ChatRequest request,
        RequestBudget budget,
        List<ConversationEntry> conversation,
        List<Capability> capabilities,
        Set<ToolName> runtimeTools
    ) {
        EnumSet<ToolName> candidates =
            EnumSet.noneOf(ToolName.class);
        candidates.addAll(runtimeTools);

        Set<ToolName> activeTools = executionPolicy.filter(
            candidates,
            request.toolsAllowed(),
            request.mode()
        );

        JevInput input = JevInput.fromConversation(
            conversation,
            capabilities,
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
            "Jev classification timed out.",
            clock
        ).handle((classification, failure) ->
            resolve(
                request,
                activeTools,
                classification,
                failure,
                jevStarted
            )
        );
    }

    private PlanningDecision resolve(
        EmbeddedBrain.ChatRequest request,
        Set<ToolName> activeTools,
        JevClassification classification,
        Throwable failure,
        Instant jevStarted
    ) {
        long latency = elapsedMillis(jevStarted, clock);
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
            ReasoningLevel reasoning = reasoningPolicy.fallback();

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
            return new PlanningDecision(fallback, reasoning);
        }

        log.debug(
            JarvisEvents.JEV_COMPLETED,
            JarvisFields.of(
                "requestId", request.requestId(),
                "route", classification.category(),
                "reasoning", classification.reasoning(),
                "engagement", classification.engagement(),
                "confidence", classification.confidence(),
                "engagementConfidence",
                classification.engagementConfidence(),
                "reasoningConfidence",
                classification.reasoningConfidence(),
                "latencyMs", latency
            )
        );

        DeterministicRoutePolicy.RoutingDecision routing =
            routePolicy.route(classification, activeTools);
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
        return new PlanningDecision(routing, reasoning);
    }

    private void logPlanning(
        EmbeddedBrain.ChatRequest request,
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

    record PlanningDecision(
        DeterministicRoutePolicy.RoutingDecision routing,
        ReasoningLevel reasoningLevel
    ) {
        PlanningDecision {
            Objects.requireNonNull(routing, "routing");
            Objects.requireNonNull(
                reasoningLevel,
                "reasoningLevel"
            );
        }
    }
}
