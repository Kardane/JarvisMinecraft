package io.github.kardane.jarvisminecraft.paper.chat;

import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess;
import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;
import io.papermc.paper.event.player.ChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;

@SuppressWarnings("deprecation")
public final class PaperChatListener implements Listener {
    private static final String SESSION_RULES =
        "대화를 시작합니다. 120초 동안 후속 대화가 이어집니다. "
            + "'대화 끝'으로 종료하고, '!내용'은 이번 메시지만 일반 채팅으로 보냅니다.";

    private final ChatSessionManager sessions;
    private final BrainGateway brain;
    private final PaperPlatformAccess platform;
    private final ServerScheduler scheduler;
    private final PlainTextComponentSerializer plain =
        PlainTextComponentSerializer.plainText();

    public PaperChatListener(
        ChatSessionManager sessions,
        BrainGateway brain,
        PaperPlatformAccess platform,
        ServerScheduler scheduler
    ) {
        this.sessions = sessions;
        this.brain = brain;
        this.platform = platform;
        this.scheduler = scheduler;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(ChatEvent event) {
        Player player = event.getPlayer();
        UUID requesterUuid = player.getUniqueId();
        boolean currentOperator = player.isOnline() && player.isOp();
        String text = plain.serialize(event.message());

        ChatSessionManager.Decision decision =
            sessions.accept(requesterUuid, currentOperator, text);

        if (!currentOperator) {
            brain.cancelActor(requesterUuid, CancelReason.OP_REVOKED);
        }

        switch (decision.kind()) {
            case PUBLIC_CHAT -> {
                // Leave Paper's normal chat path unchanged.
            }
            case PUBLIC_ESCAPE -> event.message(Component.text(decision.text()));
            case END -> {
                event.setCancelled(true);
                brain.cancelSession(
                    requesterUuid,
                    decision.sessionId(),
                    CancelReason.SESSION_ENDED
                );
                platform.sendPrivatePlain(requesterUuid, "대화를 종료했습니다.");
            }
            case FORWARD -> {
                event.setCancelled(true);
                if (decision.started()) {
                    platform.sendPrivatePlain(requesterUuid, SESSION_RULES);
                }

                brain.submitChat(
                    requesterUuid,
                    player.getName(),
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
                                "자비스 요청을 현재 처리하지 못했습니다."
                            );
                        }
                        return CompletableFuture.completedFuture(null);
                    });
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID requesterUuid = event.getPlayer().getUniqueId();
        sessions.invalidate(requesterUuid);
        brain.cancelActor(requesterUuid, CancelReason.CLIENT_DISCONNECTED);
    }
}
