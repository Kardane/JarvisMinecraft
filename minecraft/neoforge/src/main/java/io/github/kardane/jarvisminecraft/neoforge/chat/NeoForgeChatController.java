package io.github.kardane.jarvisminecraft.neoforge.chat;

import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.chat.InteractionCoordinator;
import io.github.kardane.jarvisminecraft.common.chat.InteractionDecision;
import io.github.kardane.jarvisminecraft.common.chat.PlayerIdentity;
import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.neoforge.platform.NeoForgePlatformAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;

public final class NeoForgeChatController {
    private final MinecraftServer server;
    private final ChatSessionManager sessions;
    private final InteractionCoordinator interactions;
    private final BrainGateway brain;
    private final NeoForgePlatformAccess platform;
    private final ServerScheduler scheduler;
    private final Logger logger;
    private boolean offThreadWarningLogged;
    private int tickCounter;

    public NeoForgeChatController(
        MinecraftServer server,
        ChatSessionManager sessions,
        BrainGateway brain,
        NeoForgePlatformAccess platform,
        ServerScheduler scheduler,
        Logger logger
    ) {
        this(
            server,
            sessions,
            new InteractionCoordinator(
                sessions,
                new ConfigManager(JarvisConfig::defaults)
            ),
            brain,
            platform,
            scheduler,
            logger
        );
    }

    public NeoForgeChatController(
        MinecraftServer server,
        ChatSessionManager sessions,
        InteractionCoordinator interactions,
        BrainGateway brain,
        NeoForgePlatformAccess platform,
        ServerScheduler scheduler,
        Logger logger
    ) {
        this.server = server;
        this.sessions = sessions;
        this.interactions = interactions;
        this.brain = brain;
        this.platform = platform;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    public void onChat(ServerChatEvent event) {
        if (event.getPlayer().getServer() != server) {
            return;
        }

        if (!server.isSameThread()) {
            if (!offThreadWarningLogged) {
                offThreadWarningLogged = true;
                logger.warning(
                    "NeoForge ServerChatEvent ran off the server thread; "
                        + "JARVIS interception is disabled for those callbacks until T10 verifies the runtime."
                );
            }
            return;
        }

        ServerPlayer sender = event.getPlayer();
        UUID requesterUuid = sender.getUUID();
        boolean currentOperator =
            platform.isOnlineOperator(requesterUuid);
        PlayerIdentity identity = new PlayerIdentity(
            requesterUuid,
            sender.getGameProfile().getName(),
            true,
            currentOperator
        );

        InteractionDecision decision =
            interactions.accept(identity, event.getRawText());

        if (decision.accessRevoked()) {
            brain.cancelActor(
                requesterUuid,
                CancelReason.OP_REVOKED
            );
        }

        switch (decision.kind()) {
            case PUBLIC_CHAT -> {
                // Preserve normal chat and observe it for ACTIVE mode.
                if (interactions.isAuthorized(identity)) {
                    brain.considerProactive(
                        requesterUuid,
                        sender.getGameProfile().getName(),
                        event.getRawText()
                    );
                }
            }
            case PUBLIC_ESCAPE ->
                event.setMessage(Component.literal(decision.text()));
            case END -> {
                brain.cancelSession(
                    requesterUuid,
                    decision.sessionId(),
                    CancelReason.SESSION_ENDED
                );
                platform.sendPublicPlain("대화를 종료했습니다.");
            }
            case FORWARD -> {
                brain.submitChat(
                    requesterUuid,
                    sender.getGameProfile().getName(),
                    decision.sessionId(),
                    decision.mode(),
                    decision.text()
                ).whenComplete((sent, failure) -> {
                    if (
                        failure == null
                            && Boolean.TRUE.equals(sent)
                    ) {
                        return;
                    }
                    scheduler.submit(() -> {
                        if (isCurrentlyAuthorized(requesterUuid)) {
                            platform.sendPublicPlain(
                                "자비스 요청을 현재 처리하지 못했습니다."
                            );
                        }
                        return CompletableFuture.completedFuture(null);
                    });
                });
            }
        }
    }

    public void onDisconnect(ServerPlayer player) {
        UUID requesterUuid = player.getUUID();
        sessions.invalidate(requesterUuid);
        brain.cancelActor(
            requesterUuid,
            CancelReason.CLIENT_DISCONNECTED
        );
    }

    public void onServerTick() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                "NeoForge session sweep must run on the server thread."
            );
        }

        tickCounter += 1;
        if (tickCounter < 20) {
            return;
        }
        tickCounter = 0;

        for (
            ChatSessionManager.SessionHandle expired
                : sessions.pruneExpired()
        ) {
            brain.cancelSession(
                expired.requesterUuid(),
                expired.sessionId(),
                CancelReason.SESSION_ENDED
            );
        }

        for (
            UUID revoked
                : interactions.pruneInvalid(
                    platform::interactionPlayer
                )
        ) {
            brain.cancelActor(
                revoked,
                CancelReason.OP_REVOKED
            );
        }
    }

    private boolean isCurrentlyAuthorized(UUID requesterUuid) {
        return platform.interactionPlayer(requesterUuid)
            .map(interactions::isAuthorized)
            .orElse(false);
    }

}
