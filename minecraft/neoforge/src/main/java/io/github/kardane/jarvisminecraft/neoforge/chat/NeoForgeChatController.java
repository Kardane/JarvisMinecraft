package io.github.kardane.jarvisminecraft.neoforge.chat;

import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.neoforge.platform.NeoForgePlatformAccess;
import io.github.kardane.jarvisminecraft.neoforge.transport.NeoForgeBrainConnection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;

public final class NeoForgeChatController {
    private static final String SESSION_RULES =
        "대화를 시작합니다. 120초 동안 후속 대화가 이어집니다. "
            + "'대화 끝'으로 종료하고, '!내용'은 이번 메시지만 일반 채팅으로 보냅니다.";

    private final MinecraftServer server;
    private final ChatSessionManager sessions;
    private final NeoForgeBrainConnection brain;
    private final NeoForgePlatformAccess platform;
    private final ServerScheduler scheduler;
    private final Logger logger;
    private boolean offThreadWarningLogged;
    private int tickCounter;

    public NeoForgeChatController(
        MinecraftServer server,
        ChatSessionManager sessions,
        NeoForgeBrainConnection brain,
        NeoForgePlatformAccess platform,
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
        boolean currentOperator = platform.isOnlineOperator(requesterUuid);

        ChatSessionManager.Decision decision =
            sessions.accept(requesterUuid, currentOperator, event.getRawText());

        if (!currentOperator) {
            brain.cancelActor(requesterUuid, CancelReason.OP_REVOKED);
        }

        switch (decision.kind()) {
            case PUBLIC_CHAT -> {
                // Preserve normal NeoForge chat handling.
            }
            case PUBLIC_ESCAPE -> event.setMessage(Component.literal(decision.text()));
            case END -> {
                event.setCanceled(true);
                brain.cancelSession(
                    requesterUuid,
                    decision.sessionId(),
                    CancelReason.SESSION_ENDED
                );
                platform.sendPrivatePlain(requesterUuid, "대화를 종료했습니다.");
            }
            case FORWARD -> {
                event.setCanceled(true);
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
            }
        }
    }

    public void onDisconnect(ServerPlayer player) {
        UUID requesterUuid = player.getUUID();
        sessions.invalidate(requesterUuid);
        brain.cancelActor(requesterUuid, CancelReason.CLIENT_DISCONNECTED);
    }

    public void onServerTick() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("NeoForge session sweep must run on the server thread.");
        }

        tickCounter += 1;
        if (tickCounter < 20) {
            return;
        }
        tickCounter = 0;

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
