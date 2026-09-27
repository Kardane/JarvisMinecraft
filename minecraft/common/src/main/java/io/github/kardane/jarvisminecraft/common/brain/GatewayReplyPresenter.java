package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.brain.ai.LunaStep;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.chat.InteractionCoordinator;
import io.github.kardane.jarvisminecraft.common.chat.StyledChatMessage;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
import io.github.kardane.jarvisminecraft.common.platform.AdapterPlatformAccess;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.unwrap;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

final class GatewayReplyPresenter {
    private final EmbeddedBrain brain;
    private final ChatSessionManager sessions;
    private final InteractionCoordinator interactions;
    private final ProgressNotifier progressNotifier;
    private final AdapterPlatformAccess platform;
    private final ServerScheduler serverScheduler;
    private final BooleanSupplier running;

    GatewayReplyPresenter(
        EmbeddedBrain brain,
        ChatSessionManager sessions,
        InteractionCoordinator interactions,
        ProgressNotifier progressNotifier,
        AdapterPlatformAccess platform,
        ServerScheduler serverScheduler,
        BooleanSupplier running
    ) {
        this.brain = Objects.requireNonNull(brain, "brain");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.interactions = Objects.requireNonNull(
            interactions,
            "interactions"
        );
        this.progressNotifier = Objects.requireNonNull(
            progressNotifier,
            "progressNotifier"
        );
        this.platform = Objects.requireNonNull(platform, "platform");
        this.serverScheduler = Objects.requireNonNull(
            serverScheduler,
            "serverScheduler"
        );
        this.running = Objects.requireNonNull(running, "running");
    }

    ProgressNotifier.ProgressHandle beginProgress(
        UUID requesterUuid,
        UUID sessionId,
        UUID requestId,
        String mode,
        JarvisConfig.Response responseConfig
    ) {
        if ("PROACTIVE".equalsIgnoreCase(mode)) {
            return progressNotifier.completedHandle();
        }

        JarvisConfig.WaitingMessage waiting =
            responseConfig.waitingMessage();
        if (!waiting.enabled()) {
            return progressNotifier.completedHandle();
        }

        int index = Math.floorMod(
            requestId.hashCode(),
            waiting.messages().size()
        );
        String progressText =
            waiting.messages().get(index);

        return progressNotifier.schedule(
            Duration.ofMillis(
                waiting.thresholdMillis()
            ),
            handle -> scheduleDelivery(() -> {
                if (
                    handle.completed()
                        || !isInteractionAuthorized(requesterUuid)
                        || !sessions.isActive(
                            requesterUuid,
                            sessionId
                        )
                ) {
                    return;
                }
                platform.sendPublicStyled(
                    styled(
                        responseConfig,
                        progressText
                    )
                );
            })
        );
    }

    void deliverReply(
        UUID requesterUuid,
        UUID sessionId,
        EmbeddedBrain.Reply reply,
        JarvisConfig.Response responseConfig,
        long latencyMillis
    ) {
        scheduleDelivery(() -> {
            if (
                !isInteractionAuthorized(requesterUuid)
                    || !sessions.isActive(
                        requesterUuid,
                        sessionId
                    )
            ) {
                return;
            }

            platform.sendPublicStyled(
                styled(
                    responseConfig,
                    reply.text(),
                    reply.usage(),
                    latencyMillis
                )
            );
            playResponseSound(
                requesterUuid,
                responseConfig
            );

            if (
                reply.sessionState()
                    == LunaStep.SessionState.END
            ) {
                sessions.end(
                    requesterUuid,
                    sessionId
                );
                brain.cancelSession(
                    requesterUuid,
                    sessionId
                );
            }
        });
    }

    void deliverFailure(
        UUID requesterUuid,
        UUID sessionId,
        Throwable failure,
        JarvisConfig.Response responseConfig,
        long latencyMillis
    ) {
        Throwable cause = unwrap(failure);
        ErrorCode code =
            cause instanceof ProtocolException protocol
                ? protocol.code()
                : ErrorCode.INTERNAL;

        if (
            code == ErrorCode.UNAUTHORIZED
                || code == ErrorCode.CANCELLED
        ) {
            return;
        }

        String text = safeErrorText(code);
        scheduleDelivery(() -> {
            if (
                isInteractionAuthorized(requesterUuid)
                    && sessions.isActive(
                        requesterUuid,
                        sessionId
                    )
            ) {
                platform.sendPublicStyled(
                    styled(
                        responseConfig,
                        text,
                        LunaStep.Usage.unavailable(),
                        latencyMillis
                    )
                );
                playResponseSound(
                    requesterUuid,
                    responseConfig
                );
            }
        });
    }

    private StyledChatMessage styled(
        JarvisConfig.Response responseConfig,
        String body
    ) {
        return StyledChatMessage.fromConfiguredPrefix(
            responseConfig.prefix(),
            body
        );
    }

    private StyledChatMessage styled(
        JarvisConfig.Response responseConfig,
        String body,
        LunaStep.Usage usage,
        long latencyMillis
    ) {
        StyledChatMessage message = styled(
            responseConfig,
            body
        );
        JarvisConfig.ResponseMetrics metrics =
            responseConfig.metrics();
        if (!metrics.enabled()) {
            return message;
        }
        return message.withHoverSuffix(
            " " + metrics.icon(),
            metricsTooltip(
                usage,
                latencyMillis
            )
        );
    }

    private String metricsTooltip(
        LunaStep.Usage usage,
        long latencyMillis
    ) {
        StringBuilder tooltip = new StringBuilder();
        if (usage.complete()) {
            tooltip.append("Luna 토큰: ")
                .append(usage.totalTokens())
                .append("\n입력: ")
                .append(usage.inputTokens())
                .append(" / 출력: ")
                .append(usage.outputTokens());
        } else {
            tooltip.append("Luna 토큰: 확인 불가");
        }
        tooltip.append("\n처리 시간: ");
        if (latencyMillis < 1_000L) {
            tooltip.append(latencyMillis)
                .append(" ms");
        } else {
            tooltip.append(
                String.format(
                    Locale.ROOT,
                    "%.2f s",
                    latencyMillis / 1_000.0
                )
            );
        }
        return tooltip.toString();
    }

    private void playResponseSound(
        UUID requesterUuid,
        JarvisConfig.Response responseConfig
    ) {
        JarvisConfig.Sound sound =
            responseConfig.sound();
        if (!sound.enabled()) {
            return;
        }
        try {
            platform.playResponseSound(
                requesterUuid,
                sound.id(),
                (float) sound.volume(),
                (float) sound.pitch()
            );
        } catch (RuntimeException ignored) {
            // Feedback failure must not turn a completed response into failure.
        }
    }

    private boolean isInteractionAuthorized(
        UUID requesterUuid
    ) {
        return platform
            .interactionPlayer(requesterUuid)
            .map(interactions::isAuthorized)
            .orElse(false);
    }

    private void scheduleDelivery(Runnable delivery) {
        if (!running.getAsBoolean()) {
            return;
        }
        try {
            serverScheduler.submit(() -> {
                if (!running.getAsBoolean()) {
                    return CompletableFuture.completedFuture(
                        null
                    );
                }
                delivery.run();
                return CompletableFuture.completedFuture(
                    null
                );
            });
        } catch (RuntimeException ignored) {
            // Server shutdown or scheduler rejection: do not retry delivery.
        }
    }

    private String safeErrorText(ErrorCode code) {
        return switch (code) {
            case BUSY ->
                "자비스가 현재 처리 가능한 요청 한도에 도달했습니다. 잠시 후 다시 말해 주세요.";
            case TIMEOUT ->
                "요청을 제한 시간 안에 완료하지 못했습니다. 다시 시도해 주세요.";
            case OUTCOME_UNKNOWN ->
                "작업 결과를 안전하게 확인할 수 없습니다. 자동으로 다시 실행하지 않았습니다.";
            case UNSUPPORTED ->
                "현재 이 서버에서는 해당 작업을 사용할 수 없습니다.";
            case INVALID_ARGUMENT,
                AMBIGUOUS_TARGET,
                NOT_FOUND ->
                "요청을 안전하게 확정할 수 없습니다. 대상을 더 명확하게 말해 주세요.";
            case PROVIDER_UNAVAILABLE ->
                "필요한 서버 기능이 현재 응답하지 않습니다.";
            case INTERNAL ->
                "자비스 처리 중 내부 문제가 발생했습니다.";
            case UNAUTHORIZED, CANCELLED ->
                "요청을 계속 처리할 수 없습니다.";
        };
    }
}
