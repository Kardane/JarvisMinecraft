package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.chat.InteractionCoordinator;
import io.github.kardane.jarvisminecraft.common.chat.PlayerIdentity;
import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
import io.github.kardane.jarvisminecraft.common.logging.JarvisEvents;
import io.github.kardane.jarvisminecraft.common.logging.JarvisFields;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.platform.AdapterPlatformAccess;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.unwrap;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

final class GatewayRequestCoordinator {
    private final EmbeddedBrain brain;
    private final ChatSessionManager sessions;
    private final InteractionCoordinator interactions;
    private final ConfigManager configManager;
    private final AdapterPlatformAccess platform;
    private final Clock clock;
    private final JarvisLog log;
    private final GatewayReplyPresenter presenter;
    private final BooleanSupplier running;

    GatewayRequestCoordinator(
        EmbeddedBrain brain,
        ChatSessionManager sessions,
        InteractionCoordinator interactions,
        ConfigManager configManager,
        AdapterPlatformAccess platform,
        Clock clock,
        JarvisLog log,
        GatewayReplyPresenter presenter,
        BooleanSupplier running
    ) {
        this.brain = Objects.requireNonNull(brain, "brain");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.interactions = Objects.requireNonNull(
            interactions,
            "interactions"
        );
        this.configManager = Objects.requireNonNull(
            configManager,
            "configManager"
        );
        this.platform = Objects.requireNonNull(platform, "platform");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.log = Objects.requireNonNull(log, "log");
        this.presenter = Objects.requireNonNull(
            presenter,
            "presenter"
        );
        this.running = Objects.requireNonNull(running, "running");
    }

    CompletionStage<Boolean> submit(
        UUID requesterUuid,
        String requesterName,
        UUID sessionId,
        String mode,
        String text
    ) {
        if (!running.getAsBoolean()) {
            return CompletableFuture.completedFuture(false);
        }

        if (!platform.isServerThread()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException(
                    "Embedded Brain submitChat must be called from the server thread."
                )
            );
        }

        PlayerIdentity currentPlayer = platform
            .interactionPlayer(requesterUuid)
            .orElse(null);
        if (
            currentPlayer == null
                || !interactions.isAuthorized(currentPlayer)
        ) {
            return CompletableFuture.completedFuture(false);
        }
        if (!sessions.isActive(requesterUuid, sessionId)) {
            return CompletableFuture.completedFuture(false);
        }

        Instant now = clock.instant();
        EmbeddedBrain.ChatRequest request =
            new EmbeddedBrain.ChatRequest(
                UUID.randomUUID(),
                requesterUuid,
                requesterName,
                sessionId,
                mode,
                text,
                now,
                now.plusMillis(
                    RequestBudget.MAX_REQUEST_MILLIS
                ),
                currentPlayer.operator()
            );

        log.debug(
            JarvisEvents.REQUEST_ACCEPTED,
            JarvisFields.of(
                "requestId", request.requestId(),
                "sessionId", sessionId,
                "requesterUuid", requesterUuid,
                "origin", mode
            )
        );

        JarvisConfig.Response responseConfig =
            configManager.current().response();

        final CompletionStage<EmbeddedBrain.Reply> processing;
        try {
            processing = brain.submit(request);
        } catch (RuntimeException failure) {
            logFailure(request, failure);
            return CompletableFuture.completedFuture(false);
        }

        ProgressNotifier.ProgressHandle progress =
            presenter.beginProgress(
                requesterUuid,
                sessionId,
                request.requestId(),
                mode,
                responseConfig
            );

        processing.whenComplete((reply, failure) -> {
            progress.complete();
            long latencyMillis = Math.max(
                0L,
                Duration.between(
                    request.receivedAt(),
                    clock.instant()
                ).toMillis()
            );
            if (failure == null) {
                log.info(
                    JarvisEvents.REQUEST_COMPLETED,
                    JarvisFields.of(
                        "requestId", request.requestId(),
                        "sessionId", request.sessionId(),
                        "requesterUuid", request.requesterUuid(),
                        "origin", request.mode(),
                        "latencyMs", latencyMillis
                    )
                );
                presenter.deliverReply(
                    requesterUuid,
                    sessionId,
                    reply,
                    responseConfig,
                    latencyMillis
                );
            } else {
                logFailure(request, failure);
                presenter.deliverFailure(
                    requesterUuid,
                    sessionId,
                    failure,
                    responseConfig,
                    latencyMillis
                );
            }
        });

        return CompletableFuture.completedFuture(true);
    }

    private void logFailure(
        EmbeddedBrain.ChatRequest request,
        Throwable failure
    ) {
        Throwable cause = unwrap(failure);
        ErrorCode code =
            cause instanceof ProtocolException protocol
                ? protocol.code()
                : ErrorCode.INTERNAL;

        log.warn(
            JarvisEvents.REQUEST_FAILED,
            JarvisFields.of(
                "requestId", request.requestId(),
                "sessionId", request.sessionId(),
                "requesterUuid", request.requesterUuid(),
                "origin", request.mode(),
                "errorCode", code,
                "errorClass",
                cause.getClass().getSimpleName(),
                "latencyMs",
                Math.max(
                    0L,
                    Duration.between(
                        request.receivedAt(),
                        clock.instant()
                    ).toMillis()
                )
            )
        );
    }
}
