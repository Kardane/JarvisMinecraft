package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.audit.AuditArgumentSummaries;
import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaStep;
import io.github.kardane.jarvisminecraft.common.logging.JarvisEvents;
import io.github.kardane.jarvisminecraft.common.logging.JarvisFields;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.AuditSink;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ExecutionPolicy;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.withDeadline;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

final class ToolExecutionSupport {
    private final String serverId;
    private final ConversationHistoryStore history;
    private final LunaClient luna;
    private final ExecutionPolicy executionPolicy;
    private final AuditSink audit;
    private final CommonRuntime.ExecutionRuntime toolRuntime;
    private final BrainRuntimeGuard guard;
    private final Clock clock;
    private final JarvisLog log;

    ToolExecutionSupport(
        String serverId,
        ConversationHistoryStore history,
        LunaClient luna,
        ExecutionPolicy executionPolicy,
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
        this.audit = Objects.requireNonNull(audit, "audit");
        this.toolRuntime = Objects.requireNonNull(
            toolRuntime,
            "toolRuntime"
        );
        this.guard = Objects.requireNonNull(guard, "guard");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.log = Objects.requireNonNull(log, "log");
    }

    Set<ToolName> activeTools() {
        return toolRuntime.activeTools();
    }

    Instant now() {
        return clock.instant();
    }

    boolean stopped() {
        return guard.stopped();
    }

    void assertRunning() {
        guard.assertRunning();
    }

    void assertSession(
        UUID requesterUuid,
        UUID sessionId
    ) {
        guard.assertSession(requesterUuid, sessionId);
    }

    CompletionStage<ToolResult> execute(
        CommonRuntime.ToolInvocation invocation
    ) {
        return toolRuntime.execute(invocation);
    }

    CompletionStage<Void> requirePreAudit(
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

    CompletionStage<Void> tryPostAudit(
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

    AuditSink.AuditEvent auditEvent(
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

    void appendToolResult(
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

    void logToolDenied(
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

    void logToolStarted(
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

    void logToolCompleted(
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

    ErrorCode toolErrorCode(ToolResult result) {
        return result.error() == null
            ? null
            : result.error().code();
    }
}
