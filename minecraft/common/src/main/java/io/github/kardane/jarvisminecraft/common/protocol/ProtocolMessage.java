package io.github.kardane.jarvisminecraft.common.protocol;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.MessageType;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;
import static io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ErrorObject;
import static io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolArguments;
import static io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;

public record ProtocolMessage(
    String protocolVersion,
    MessageType type,
    UUID messageId,
    UUID requestId,
    String serverId,
    UUID sessionId,
    UUID requesterUuid,
    Instant sentAt,
    Instant deadlineAt,
    Payload payload,
    UUID toolCallId,
    UUID actionId
) {
    public sealed interface Payload permits
        AdapterHello, BrainHello, Capabilities, ChatMessage, ChatResponse,
        ToolRequest, ToolResultPayload, Cancel, ErrorPayload, Ping, Pong {
    }

    public record AdapterHello(
        String side,
        UUID adapterInstanceId,
        String platform,
        String minecraftVersion,
        String adapterVersion,
        String platformVersion
    ) implements Payload {
    }

    public record BrainHello(
        String side,
        UUID brainInstanceId,
        String brainVersion,
        boolean accepted
    ) implements Payload {
    }

    public record Capability(String name, String source, String version) {
    }

    public record Limits(
        int maxMessageBytes,
        int maxToolCallsPerRequest,
        int maxModelRoundTripsPerRequest
    ) {
    }

    public record Capabilities(
        List<Capability> capabilities,
        List<ToolName> tools,
        Limits limits
    ) implements Payload {
    }

    public record ChatMessage(
        String requesterName,
        String text,
        String mode
    ) implements Payload {
    }

    public record ChatResponse(
        String text,
        boolean finalResponse,
        String sessionState
    ) implements Payload {
    }

    public record ToolRequest(
        ToolName tool,
        ToolArguments arguments
    ) implements Payload {
    }

    public record ToolResultPayload(
        ToolName tool,
        ToolResult result
    ) implements Payload {
    }

    public record Cancel(
        UUID targetRequestId,
        CancelReason reason
    ) implements Payload {
    }

    public record ErrorPayload(
        String scope,
        ErrorObject error
    ) implements Payload {
    }

    public record Ping(String nonce) implements Payload {
    }

    public record Pong(String nonce) implements Payload {
    }
}
