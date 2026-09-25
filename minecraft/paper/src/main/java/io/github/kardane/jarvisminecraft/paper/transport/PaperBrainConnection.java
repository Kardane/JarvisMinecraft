package io.github.kardane.jarvisminecraft.paper.transport;

import io.github.kardane.jarvisminecraft.common.protocol.Protocol;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolCodec;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolMessage;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.transport.BrainTransport;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.MessageType;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class PaperBrainConnection {
    public static final Set<ToolName> V01_TOOLS = Set.copyOf(
        EnumSet.of(
            ToolName.GET_SERVER_STATUS,
            ToolName.GET_ONLINE_PLAYERS,
            ToolName.GET_PLAYER,
            ToolName.GET_PLAYER_LOCATION,
            ToolName.GET_NEARBY_PLAYERS,
            ToolName.GET_WORLD_INFO,
            ToolName.TELEPORT_STAFF
        )
    );

    private final String serverId;
    private final String minecraftVersion;
    private final String adapterVersion;
    private final String platformVersion;
    private final Set<ToolName> activeTools;
    private final List<ProtocolMessage.Capability> capabilities;
    private final Clock clock;
    private final CommonRuntime commonRuntime;
    private final ServerScheduler serverScheduler;
    private final PaperPlatformAccess platform;
    private final BiPredicate<UUID, UUID> activeSession;
    private final BrainTransportFactory transportFactory;
    private final ReconnectScheduler reconnectScheduler;
    private final Logger logger;
    private final ProtocolCodec codec = new ProtocolCodec();
    private final RequestBindingRegistry bindings;
    private final UUID adapterInstanceId = UUID.randomUUID();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    private volatile boolean stopped;
    private volatile State state = State.DISCONNECTED;
    private volatile BrainTransport transport;
    private volatile CommonRuntime.ConnectionRuntime connectionRuntime;
    private volatile UUID connectionId;

    public PaperBrainConnection(
        String serverId,
        String minecraftVersion,
        String adapterVersion,
        String platformVersion,
        Set<ToolName> activeTools,
        List<ProtocolMessage.Capability> capabilities,
        Clock clock,
        CommonRuntime commonRuntime,
        ServerScheduler serverScheduler,
        PaperPlatformAccess platform,
        BiPredicate<UUID, UUID> activeSession,
        BrainTransportFactory transportFactory,
        ReconnectScheduler reconnectScheduler,
        Logger logger
    ) {
        this.serverId = serverId;
        this.minecraftVersion = minecraftVersion;
        this.adapterVersion = adapterVersion;
        this.platformVersion = platformVersion;
        this.activeTools = Set.copyOf(activeTools);
        this.capabilities = List.copyOf(capabilities);
        this.clock = clock;
        this.commonRuntime = commonRuntime;
        this.serverScheduler = serverScheduler;
        this.platform = platform;
        this.activeSession = activeSession;
        this.transportFactory = transportFactory;
        this.reconnectScheduler = reconnectScheduler;
        this.logger = logger;
        this.bindings = new RequestBindingRegistry(clock);
    }

    public void start() {
        stopped = false;
        connectNow();
    }

    public void stop() {
        stopped = true;
        state = State.DISCONNECTED;
        bindings.clear();
        BrainTransport current = transport;
        transport = null;
        connectionRuntime = null;
        connectionId = null;
        if (current != null) {
            current.close();
        }
    }

    public boolean active() {
        BrainTransport current = transport;
        return state == State.ACTIVE && current != null && current.connected();
    }

    public CompletionStage<Boolean> submitChat(
        UUID requesterUuid,
        String requesterName,
        UUID sessionId,
        String mode,
        String text
    ) {
        BrainTransport current = transport;
        if (!active() || current == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (!platform.isServerThread() || !platform.isOnlineOperator(requesterUuid)) {
            return CompletableFuture.completedFuture(false);
        }
        if (!activeSession.test(requesterUuid, sessionId)) {
            return CompletableFuture.completedFuture(false);
        }

        Instant sentAt = clock.instant();
        Instant deadlineAt = sentAt.plusSeconds(30);
        UUID requestId = UUID.randomUUID();
        ProtocolMessage message = new ProtocolMessage(
            Protocol.VERSION,
            MessageType.CHAT_MESSAGE,
            UUID.randomUUID(),
            requestId,
            serverId,
            sessionId,
            requesterUuid,
            sentAt,
            deadlineAt,
            new ProtocolMessage.ChatMessage(requesterName, text, mode),
            null,
            null
        );
        bindings.register(requestId, requesterUuid, sessionId, deadlineAt);

        return current.send(codec.encode(message)).handle((ignored, failure) -> {
            if (failure != null) {
                bindings.complete(requestId);
                return false;
            }
            return true;
        });
    }

    public void cancelSession(
        UUID requesterUuid,
        UUID sessionId,
        CancelReason reason
    ) {
        for (UUID requestId : bindings.invalidateSession(requesterUuid, sessionId)) {
            sendCancel(requestId, requesterUuid, sessionId, reason);
        }
    }

    public void cancelActor(UUID requesterUuid, CancelReason reason) {
        for (UUID requestId : bindings.invalidateActor(requesterUuid)) {
            sendCancel(requestId, requesterUuid, null, reason);
        }
    }

    private void connectNow() {
        if (stopped) {
            return;
        }
        reconnectScheduled.set(false);

        BrainTransport next = transportFactory.create();
        transport = next;
        state = State.CONNECTING;

        next.connect(new Listener(next)).whenComplete((ignored, failure) -> {
            if (failure != null && transport == next && !stopped) {
                handleDisconnect(next, "connect failed");
            }
        });
    }

    private void onConnected(BrainTransport source) {
        if (stopped || transport != source) {
            source.close();
            return;
        }

        connectionId = UUID.randomUUID();
        connectionRuntime = commonRuntime.openConnection(
            connectionId,
            serverId,
            activeTools
        );
        state = State.WAITING_BRAIN_HELLO;

        ProtocolMessage hello = new ProtocolMessage(
            Protocol.VERSION,
            MessageType.HELLO,
            UUID.randomUUID(),
            null,
            serverId,
            null,
            null,
            clock.instant(),
            null,
            new ProtocolMessage.AdapterHello(
                "adapter",
                adapterInstanceId,
                "paper",
                minecraftVersion,
                adapterVersion,
                platformVersion
            ),
            null,
            null
        );
        source.send(codec.encode(hello)).whenComplete((ignored, failure) -> {
            if (failure != null) {
                handleDisconnect(source, "hello send failed");
            }
        });
    }

    private void onMessage(BrainTransport source, String raw) {
        if (stopped || transport != source) {
            return;
        }

        final ProtocolMessage message;
        try {
            message = codec.decode(raw);
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "JARVIS Brain sent an invalid protocol message.");
            handleDisconnect(source, "protocol decode failure");
            return;
        }

        if (!serverId.equals(message.serverId())) {
            handleDisconnect(source, "server binding mismatch");
            return;
        }

        if (state == State.WAITING_BRAIN_HELLO) {
            if (
                message.type() != MessageType.HELLO
                    || !(message.payload() instanceof ProtocolMessage.BrainHello brainHello)
                    || !brainHello.accepted()
            ) {
                handleDisconnect(source, "Brain hello was not accepted");
                return;
            }
            sendCapabilities(source);
            return;
        }

        if (state != State.ACTIVE) {
            return;
        }

        switch (message.type()) {
            case TOOL_REQUEST -> handleToolRequest(source, message);
            case CHAT_RESPONSE -> handleChatResponse(message);
            case PING -> sendPong(source, message);
            case CANCEL -> {
                if (message.payload() instanceof ProtocolMessage.Cancel cancel) {
                    bindings.complete(cancel.targetRequestId());
                }
            }
            case ERROR -> logger.warning("JARVIS Brain reported a protocol/request error.");
            case PONG -> {
                // Keepalive acknowledgement.
            }
            default -> {
                // Directionally invalid messages are ignored rather than executed.
            }
        }
    }

    private void sendCapabilities(BrainTransport source) {
        ProtocolMessage message = new ProtocolMessage(
            Protocol.VERSION,
            MessageType.CAPABILITIES,
            UUID.randomUUID(),
            null,
            serverId,
            null,
            null,
            clock.instant(),
            null,
            new ProtocolMessage.Capabilities(
                capabilities,
                activeTools.stream().sorted(java.util.Comparator.comparing(ToolName::wireName)).toList(),
                new ProtocolMessage.Limits(Protocol.MAX_MESSAGE_BYTES, 8, 4)
            ),
            null,
            null
        );

        source.send(codec.encode(message)).whenComplete((ignored, failure) -> {
            if (failure != null) {
                handleDisconnect(source, "capabilities send failed");
            } else if (transport == source && !stopped) {
                state = State.ACTIVE;
            }
        });
    }

    private void handleToolRequest(BrainTransport source, ProtocolMessage message) {
        if (!bindings.matches(message) || !activeSession.test(message.requesterUuid(), message.sessionId())) {
            sendToolResult(
                source,
                message,
                ToolResult.error(
                    ErrorCode.UNAUTHORIZED,
                    "Tool request is not bound to an active Paper operator request.",
                    false,
                    clock.instant(),
                    "Paper"
                )
            );
            return;
        }

        CommonRuntime.ConnectionRuntime runtime = connectionRuntime;
        if (runtime == null) {
            sendToolResult(
                source,
                message,
                ToolResult.error(
                    ErrorCode.CANCELLED,
                    "Paper connection is no longer active.",
                    false,
                    clock.instant(),
                    "Paper"
                )
            );
            return;
        }

        serverScheduler.submit(() -> runtime.execute(message)).whenComplete((result, failure) -> {
            ToolResult terminal = failure == null
                ? result
                : ToolResult.error(
                    ErrorCode.INTERNAL,
                    "Paper Tool execution failed.",
                    false,
                    clock.instant(),
                    "Paper"
                );
            sendToolResult(source, message, terminal);
        });
    }

    private void sendToolResult(
        BrainTransport source,
        ProtocolMessage request,
        ToolResult result
    ) {
        if (transport != source || stopped) {
            return;
        }
        ProtocolMessage.ToolRequest toolRequest =
            (ProtocolMessage.ToolRequest) request.payload();
        ProtocolMessage response = new ProtocolMessage(
            Protocol.VERSION,
            MessageType.TOOL_RESULT,
            UUID.randomUUID(),
            request.requestId(),
            serverId,
            request.sessionId(),
            request.requesterUuid(),
            clock.instant(),
            request.deadlineAt(),
            new ProtocolMessage.ToolResultPayload(toolRequest.tool(), result),
            request.toolCallId(),
            request.actionId()
        );
        source.send(codec.encode(response));
    }

    private void handleChatResponse(ProtocolMessage message) {
        if (!bindings.matches(message) || !activeSession.test(message.requesterUuid(), message.sessionId())) {
            return;
        }
        if (!(message.payload() instanceof ProtocolMessage.ChatResponse response)) {
            return;
        }

        serverScheduler.submit(() -> {
            if (
                !bindings.matches(message)
                    || !activeSession.test(message.requesterUuid(), message.sessionId())
                    || !platform.isOnlineOperator(message.requesterUuid())
            ) {
                return CompletableFuture.completedFuture(null);
            }

            platform.sendPrivatePlain(message.requesterUuid(), response.text());
            if (response.finalResponse()) {
                bindings.complete(message.requestId());
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private void sendPong(BrainTransport source, ProtocolMessage pingMessage) {
        if (!(pingMessage.payload() instanceof ProtocolMessage.Ping ping)) {
            return;
        }
        ProtocolMessage pong = new ProtocolMessage(
            Protocol.VERSION,
            MessageType.PONG,
            UUID.randomUUID(),
            null,
            serverId,
            null,
            null,
            clock.instant(),
            null,
            new ProtocolMessage.Pong(ping.nonce()),
            null,
            null
        );
        source.send(codec.encode(pong));
    }

    private void sendCancel(
        UUID requestId,
        UUID requesterUuid,
        UUID sessionId,
        CancelReason reason
    ) {
        BrainTransport current = transport;
        if (!active() || current == null) {
            return;
        }
        ProtocolMessage cancel = new ProtocolMessage(
            Protocol.VERSION,
            MessageType.CANCEL,
            UUID.randomUUID(),
            requestId,
            serverId,
            sessionId,
            requesterUuid,
            clock.instant(),
            clock.instant().plusSeconds(5),
            new ProtocolMessage.Cancel(requestId, reason),
            null,
            null
        );
        current.send(codec.encode(cancel));
    }

    private void handleDisconnect(BrainTransport source, String reason) {
        if (transport != source) {
            return;
        }

        state = State.DISCONNECTED;
        bindings.clear();
        connectionRuntime = null;
        connectionId = null;
        transport = null;
        try {
            source.close();
        } catch (RuntimeException ignored) {
            // Best effort.
        }

        if (!stopped && reconnectScheduled.compareAndSet(false, true)) {
            logger.fine("JARVIS Brain connection closed: " + reason);
            reconnectScheduler.schedule(this::connectNow);
        }
    }

    public interface BrainTransportFactory {
        BrainTransport create();
    }

    @FunctionalInterface
    public interface ReconnectScheduler {
        void schedule(Runnable reconnect);
    }

    private enum State {
        DISCONNECTED,
        CONNECTING,
        WAITING_BRAIN_HELLO,
        ACTIVE
    }

    private final class Listener implements BrainTransport.Listener {
        private final BrainTransport source;

        private Listener(BrainTransport source) {
            this.source = source;
        }

        @Override
        public void onConnected() {
            PaperBrainConnection.this.onConnected(source);
        }

        @Override
        public void onMessage(String message) {
            PaperBrainConnection.this.onMessage(source, message);
        }

        @Override
        public void onDisconnected(int statusCode, String reason) {
            handleDisconnect(source, "remote close " + statusCode);
        }

        @Override
        public void onFailure(Throwable failure) {
            handleDisconnect(source, "transport failure");
        }
    }
}
