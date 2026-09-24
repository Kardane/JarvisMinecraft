package io.github.kardane.jarvisminecraft.common.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.Risk;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public interface AuditSink {
    CompletionStage<Boolean> record(AuditEvent event);

    record AuditEvent(
        Instant timestamp,
        String serverId,
        UUID requesterUuid,
        UUID requestId,
        UUID toolCallId,
        UUID actionId,
        ToolName tool,
        Risk risk,
        Map<String, Object> argumentSummary,
        String outcome,
        String source,
        long latencyMillis,
        String modelId,
        String fallbackReason
    ) {
    }

    static AuditSink noOp() {
        return event -> java.util.concurrent.CompletableFuture.completedFuture(true);
    }
}
