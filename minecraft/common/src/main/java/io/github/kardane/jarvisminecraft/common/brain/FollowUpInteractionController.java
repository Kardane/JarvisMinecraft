package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.brain.ai.JdkJevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassification;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevEngagement;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.chat.InteractionCoordinator;
import io.github.kardane.jarvisminecraft.common.chat.PlayerIdentity;
import io.github.kardane.jarvisminecraft.common.logging.JarvisEvents;
import io.github.kardane.jarvisminecraft.common.logging.JarvisFields;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.platform.AdapterPlatformAccess;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.unwrap;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

final class FollowUpInteractionController {
    static final double MIN_ENGAGEMENT_CONFIDENCE = 0.70;
    private static final long MAX_CLASSIFICATION_MILLIS = 1500L;
    private static final int MAX_IN_FLIGHT = 4;

    private final EmbeddedBrain brain;
    private final ChatSessionManager sessions;
    private final InteractionCoordinator interactions;
    private final AdapterPlatformAccess platform;
    private final ServerScheduler serverScheduler;
    private final Clock clock;
    private final JarvisLog log;
    private final BooleanSupplier running;
    private final ChatSubmitter submitter;
    private final Set<UUID> inFlightActors =
        ConcurrentHashMap.newKeySet();
    private final Semaphore inFlight =
        new Semaphore(MAX_IN_FLIGHT);

    FollowUpInteractionController(
        EmbeddedBrain brain,
        ChatSessionManager sessions,
        InteractionCoordinator interactions,
        AdapterPlatformAccess platform,
        ServerScheduler serverScheduler,
        Clock clock,
        JarvisLog log,
        BooleanSupplier running,
        ChatSubmitter submitter
    ) {
        this.brain = Objects.requireNonNull(brain, "brain");
        this.sessions = Objects.requireNonNull(
            sessions,
            "sessions"
        );
        this.interactions = Objects.requireNonNull(
            interactions,
            "interactions"
        );
        this.platform = Objects.requireNonNull(
            platform,
            "platform"
        );
        this.serverScheduler = Objects.requireNonNull(
            serverScheduler,
            "serverScheduler"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
        this.log = Objects.requireNonNull(log, "log");
        this.running = Objects.requireNonNull(
            running,
            "running"
        );
        this.submitter = Objects.requireNonNull(
            submitter,
            "submitter"
        );
    }

    CompletionStage<Boolean> consider(
        UUID requesterUuid,
        String requesterName,
        UUID sessionId,
        String text
    ) {
        Objects.requireNonNull(
            requesterUuid,
            "requesterUuid"
        );
        Objects.requireNonNull(
            requesterName,
            "requesterName"
        );
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(text, "text");

        if (
            !running.getAsBoolean()
                || !platform.isServerThread()
                || !sessions.isActive(
                    requesterUuid,
                    sessionId
                )
        ) {
            return CompletableFuture.completedFuture(false);
        }

        PlayerIdentity player = platform
            .interactionPlayer(requesterUuid)
            .orElse(null);
        if (
            player == null
                || !interactions.isAuthorized(player)
        ) {
            return CompletableFuture.completedFuture(false);
        }

        if (!inFlightActors.add(requesterUuid)) {
            logIgnored(
                requesterUuid,
                sessionId,
                "CLASSIFICATION_IN_FLIGHT",
                null
            );
            return CompletableFuture.completedFuture(false);
        }
        if (!inFlight.tryAcquire()) {
            inFlightActors.remove(requesterUuid);
            logIgnored(
                requesterUuid,
                sessionId,
                "GLOBAL_IN_FLIGHT_LIMIT",
                null
            );
            return CompletableFuture.completedFuture(false);
        }

        log.debug(
            JarvisEvents.FOLLOW_UP_CANDIDATE,
            JarvisFields.of(
                "requesterUuid", requesterUuid,
                "sessionId", sessionId
            )
        );

        Instant deadline = clock.instant()
            .plusMillis(MAX_CLASSIFICATION_MILLIS);
        final CompletionStage<JevClassification> classification;
        try {
            classification = brain.classifyFollowUp(
                requesterUuid,
                sessionId,
                text,
                deadline
            );
        } catch (RuntimeException failure) {
            release(requesterUuid);
            logFailure(
                requesterUuid,
                sessionId,
                failureReason(failure)
            );
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> result =
            new CompletableFuture<>();
        classification.whenComplete((decision, failure) -> {
            if (failure != null) {
                release(requesterUuid);
                logFailure(
                    requesterUuid,
                    sessionId,
                    failureReason(failure)
                );
                result.complete(false);
                return;
            }
            if (
                decision == null
                    || !JdkJevClassifier.MODEL.equals(
                        decision.model()
                    )
            ) {
                release(requesterUuid);
                logFailure(
                    requesterUuid,
                    sessionId,
                    "JEV_INVALID_OUTPUT"
                );
                result.complete(false);
                return;
            }
            if (
                decision.engagement()
                    != JevEngagement.RESPOND
                    || decision.engagementConfidence()
                        < MIN_ENGAGEMENT_CONFIDENCE
            ) {
                release(requesterUuid);
                logIgnored(
                    requesterUuid,
                    sessionId,
                    decision.engagement()
                        == JevEngagement.RESPOND
                            ? "CONFIDENCE_BELOW_THRESHOLD"
                            : "JEV_IGNORE",
                    decision.engagementConfidence()
                );
                result.complete(false);
                return;
            }

            scheduleSubmit(
                requesterUuid,
                requesterName,
                sessionId,
                text,
                decision.engagementConfidence(),
                result
            );
        });
        return result;
    }

    private void scheduleSubmit(
        UUID requesterUuid,
        String requesterName,
        UUID sessionId,
        String text,
        double confidence,
        CompletableFuture<Boolean> result
    ) {
        try {
            serverScheduler.submit(() ->
                activate(
                    requesterUuid,
                    requesterName,
                    sessionId,
                    text,
                    confidence
                )
            ).whenComplete((accepted, failure) -> {
                release(requesterUuid);
                if (failure != null) {
                    logFailure(
                        requesterUuid,
                        sessionId,
                        "SUBMIT_SCHEDULER_FAILED"
                    );
                    result.complete(false);
                } else {
                    result.complete(
                        Boolean.TRUE.equals(accepted)
                    );
                }
            });
        } catch (RuntimeException failure) {
            release(requesterUuid);
            logFailure(
                requesterUuid,
                sessionId,
                "SUBMIT_SCHEDULER_FAILED"
            );
            result.complete(false);
        }
    }

    private CompletionStage<Boolean> activate(
        UUID requesterUuid,
        String requesterName,
        UUID sessionId,
        String text,
        double confidence
    ) {
        if (
            !running.getAsBoolean()
                || !platform.isServerThread()
                || !sessions.isActive(
                    requesterUuid,
                    sessionId
                )
        ) {
            return CompletableFuture.completedFuture(false);
        }

        PlayerIdentity player = platform
            .interactionPlayer(requesterUuid)
            .orElse(null);
        if (
            player == null
                || !interactions.isAuthorized(player)
        ) {
            return CompletableFuture.completedFuture(false);
        }

        log.info(
            JarvisEvents.FOLLOW_UP_ACCEPTED,
            JarvisFields.of(
                "requesterUuid", requesterUuid,
                "sessionId", sessionId,
                "confidence", confidence
            )
        );
        return submitter.submit(
            requesterUuid,
            requesterName,
            sessionId,
            "FOLLOW_UP",
            text
        );
    }

    private void release(UUID requesterUuid) {
        if (inFlightActors.remove(requesterUuid)) {
            inFlight.release();
        }
    }

    private void logIgnored(
        UUID requesterUuid,
        UUID sessionId,
        String reason,
        Double confidence
    ) {
        log.debug(
            JarvisEvents.FOLLOW_UP_IGNORED,
            JarvisFields.of(
                "requesterUuid", requesterUuid,
                "sessionId", sessionId,
                "reason", reason,
                "confidence", confidence,
                "threshold",
                MIN_ENGAGEMENT_CONFIDENCE
            )
        );
    }

    private void logFailure(
        UUID requesterUuid,
        UUID sessionId,
        String reason
    ) {
        log.warn(
            JarvisEvents.FOLLOW_UP_FAILED,
            JarvisFields.of(
                "requesterUuid", requesterUuid,
                "sessionId", sessionId,
                "reason", reason
            )
        );
    }

    private String failureReason(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (
            cause instanceof ProtocolException protocol
                && protocol.code() == ErrorCode.TIMEOUT
        ) {
            return "JEV_TIMEOUT";
        }
        return "JEV_FAILED";
    }

    @FunctionalInterface
    interface ChatSubmitter {
        CompletionStage<Boolean> submit(
            UUID requesterUuid,
            String requesterName,
            UUID sessionId,
            String mode,
            String text
        );
    }
}
