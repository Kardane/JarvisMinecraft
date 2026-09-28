package io.github.kardane.jarvisminecraft.fabric.chat;

import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.chat.InteractionCoordinator;
import io.github.kardane.jarvisminecraft.common.chat.InteractionDecision;
import io.github.kardane.jarvisminecraft.common.chat.PlayerIdentity;
import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.fabric.platform.FabricPlatformAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;

public final class FabricChatController {
    private final MinecraftServer server;
    private final ChatSessionManager sessions;
    private final InteractionCoordinator interactions;
    private final BrainGateway brain;
    private final FabricPlatformAccess platform;
    private final ServerScheduler scheduler;
    private final Logger logger;
    private boolean offThreadWarningLogged;

    public FabricChatController(
        MinecraftServer server,
        ChatSessionManager sessions,
        BrainGateway brain,
        FabricPlatformAccess platform,
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

    public FabricChatController(
        MinecraftServer server,
        ChatSessionManager sessions,
        InteractionCoordinator interactions,
        BrainGateway brain,
        FabricPlatformAccess platform,
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

    public boolean allowChat(ServerPlayerEntity sender, String text) {
        if (!server.isOnThread()) {
            if (!offThreadWarningLogged) {
                offThreadWarningLogged = true;
                logger.warning(
                    "Fabric ALLOW_CHAT_MESSAGE ran off the server thread; "
                        + "JARVIS interception is disabled for those callbacks until T10 verifies the runtime."
                );
            }
            return true;
        }

        UUID requesterUuid = sender.getUuid();
        boolean currentOperator =
            platform.isOnlineOperator(requesterUuid);
        PlayerIdentity identity = new PlayerIdentity(
            requesterUuid,
            sender.getGameProfile().getName(),
            true,
            currentOperator
        );

        InteractionDecision decision =
            interactions.accept(identity, text);

        if (decision.accessRevoked()) {
            brain.cancelActor(
                requesterUuid,
                CancelReason.OP_REVOKED
            );
        }

        return switch (decision.kind()) {
            case PUBLIC_CHAT -> {
                if (interactions.isAuthorized(identity)) {
                    brain.considerProactive(
                        requesterUuid,
                        sender.getGameProfile().getName(),
                        text
                    );
                }
                yield true;
            }
            case PUBLIC_ESCAPE -> true;
            case FOLLOW_UP_CANDIDATE -> {
                brain.considerFollowUp(
                    requesterUuid,
                    sender.getGameProfile().getName(),
                    decision.sessionId(),
                    decision.text()
                );
                yield true;
            }
            case END -> {
                brain.cancelSession(
                    requesterUuid,
                    decision.sessionId(),
                    CancelReason.SESSION_ENDED
                );
                platform.sendPublicPlain("대화를 종료했습니다.");
                yield true;
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
                yield true;
            }
        };
    }

    public void onDisconnect(ServerPlayerEntity player) {
        UUID requesterUuid = player.getUuid();
        sessions.invalidate(requesterUuid);
        brain.cancelActor(
            requesterUuid,
            CancelReason.CLIENT_DISCONNECTED
        );
    }

    public void sweepSessions() {
        if (!server.isOnThread()) {
            throw new IllegalStateException(
                "Fabric session sweep must run on the server thread."
            );
        }

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
