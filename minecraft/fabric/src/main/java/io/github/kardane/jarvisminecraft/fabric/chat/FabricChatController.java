package io.github.kardane.jarvisminecraft.fabric.chat;

import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.fabric.platform.FabricPlatformAccess;
import io.github.kardane.jarvisminecraft.fabric.transport.FabricBrainConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;

public final class FabricChatController {
    private static final String SESSION_RULES =
        "대화를 시작합니다. 120초 동안 후속 대화가 이어집니다. "
            + "'대화 끝'으로 종료하고, '!내용'은 이번 메시지만 일반 채팅으로 보냅니다.";

    private final MinecraftServer server;
    private final ChatSessionManager sessions;
    private final FabricBrainConnection brain;
    private final FabricPlatformAccess platform;
    private final ServerScheduler scheduler;
    private final Logger logger;
    private boolean offThreadWarningLogged;

    public FabricChatController(
        MinecraftServer server,
        ChatSessionManager sessions,
        FabricBrainConnection brain,
        FabricPlatformAccess platform,
        ServerScheduler scheduler,
        Logger logger
    ) {
        this.server = server;
        this.sessions = sessions;
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
        boolean currentOperator = platform.isOnlineOperator(requesterUuid);
        ChatSessionManager.Decision decision =
            sessions.accept(requesterUuid, currentOperator, text);

        if (!currentOperator) {
            brain.cancelActor(requesterUuid, CancelReason.OP_REVOKED);
        }

        return switch (decision.kind()) {
            case PUBLIC_CHAT -> true;
            case PUBLIC_ESCAPE -> true;
            case END -> {
                brain.cancelSession(
                    requesterUuid,
                    decision.sessionId(),
                    CancelReason.SESSION_ENDED
                );
                platform.sendPrivatePlain(requesterUuid, "대화를 종료했습니다.");
                yield false;
            }
            case FORWARD -> {
                if (decision.started()) {
                    platform.sendPrivatePlain(requesterUuid, SESSION_RULES);
                }

                brain.submitChat(
                    requesterUuid,
                    sender.getGameProfile().getName(),
                    decision.sessionId(),
                    decision.mode(),
                    decision.text()
                ).whenComplete((sent, failure) -> {
                    if (failure == null && Boolean.TRUE.equals(sent)) {
                        return;
                    }
                    scheduler.submit(() -> {
                        if (platform.isOnlineOperator(requesterUuid)) {
                            platform.sendPrivatePlain(
                                requesterUuid,
                                "Brain에 연결되어 있지 않아 요청을 전달하지 못했습니다."
                            );
                        }
                        return CompletableFuture.completedFuture(null);
                    });
                });
                yield false;
            }
        };
    }

    public void onDisconnect(ServerPlayerEntity player) {
        UUID requesterUuid = player.getUuid();
        sessions.invalidate(requesterUuid);
        brain.cancelActor(requesterUuid, CancelReason.CLIENT_DISCONNECTED);
    }

    public void sweepSessions() {
        if (!server.isOnThread()) {
            throw new IllegalStateException("Fabric session sweep must run on the server thread.");
        }

        for (ChatSessionManager.SessionHandle expired : sessions.pruneExpired()) {
            brain.cancelSession(
                expired.requesterUuid(),
                expired.sessionId(),
                CancelReason.SESSION_ENDED
            );
        }

        for (UUID revoked : sessions.pruneInvalid(platform::isOnlineOperator)) {
            brain.cancelActor(revoked, CancelReason.OP_REVOKED);
        }
    }
}
