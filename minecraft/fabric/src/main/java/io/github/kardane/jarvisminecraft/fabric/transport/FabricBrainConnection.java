package io.github.kardane.jarvisminecraft.fabric.transport;

import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.tools.StandardMinecraftTools;
import io.github.kardane.jarvisminecraft.common.transport.AdapterBrainConnection;
import io.github.kardane.jarvisminecraft.common.transport.BrainTransport;
import io.github.kardane.jarvisminecraft.fabric.platform.FabricPlatformAccess;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BiPredicate;
import java.util.logging.Logger;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class FabricBrainConnection implements BrainGateway {
    public static final Set<ToolName> V01_TOOLS = StandardMinecraftTools.TOOLS;

    private final AdapterBrainConnection delegate;

    public FabricBrainConnection(
        String serverId,
        String minecraftVersion,
        String adapterVersion,
        String platformVersion,
        Clock clock,
        CommonRuntime commonRuntime,
        ServerScheduler serverScheduler,
        FabricPlatformAccess platform,
        BiPredicate<UUID, UUID> activeSession,
        BrainTransportFactory transportFactory,
        ReconnectScheduler reconnectScheduler,
        Logger logger
    ) {
        this.delegate = new AdapterBrainConnection(
            serverId,
            "fabric",
            "Fabric",
            minecraftVersion,
            adapterVersion,
            platformVersion,
            StandardMinecraftTools.TOOLS,
            StandardMinecraftTools.capabilities("Fabric", minecraftVersion),
            clock,
            commonRuntime,
            serverScheduler,
            platform,
            activeSession,
            transportFactory::create,
            reconnectScheduler::schedule,
            logger
        );
    }

    public void start() {
        delegate.start();
    }

    public void stop() {
        delegate.stop();
    }

    public boolean active() {
        return delegate.active();
    }

    public CompletionStage<Boolean> submitChat(
        UUID requesterUuid,
        String requesterName,
        UUID sessionId,
        String mode,
        String text
    ) {
        return delegate.submitChat(requesterUuid, requesterName, sessionId, mode, text);
    }

    public void cancelSession(UUID requesterUuid, UUID sessionId, CancelReason reason) {
        delegate.cancelSession(requesterUuid, sessionId, reason);
    }

    public void cancelActor(UUID requesterUuid, CancelReason reason) {
        delegate.cancelActor(requesterUuid, reason);
    }

    public interface BrainTransportFactory {
        BrainTransport create();
    }

    @FunctionalInterface
    public interface ReconnectScheduler {
        void schedule(Runnable reconnect);
    }
}
