package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.brain.ai.JdkJevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevEngagement;
import io.github.kardane.jarvisminecraft.common.chat.AmbientChatMessage;
import io.github.kardane.jarvisminecraft.common.chat.AmbientConversationTracker;
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
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.unwrap;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

final class ProactiveInteractionController {
    private static final Duration CLASSIFICATION_INTERVAL =
        Duration.ofSeconds(1);

    private final EmbeddedBrain brain;
    private final ChatSessionManager sessions;
    private final InteractionCoordinator interactions;
    private final ConfigManager configManager;
    private final AdapterPlatformAccess platform;
    private final ServerScheduler serverScheduler;
    private final Clock clock;
    private final JarvisLog log;
    private final BooleanSupplier running;
    private final ChatSubmitter submitter;
    private final AmbientConversationTracker ambientTracker =
        new AmbientConversationTracker();
    private final AtomicBoolean inFlight =
        new AtomicBoolean();

    private Instant cooldownUntil = Instant.EPOCH;
    private Instant classificationAfter = Instant.EPOCH;

    ProactiveInteractionController(
        EmbeddedBrain brain,
        ChatSessionManager sessions,
        InteractionCoordinator interactions,
        ConfigManager configManager,
        AdapterPlatformAccess platform,
        ServerScheduler serverScheduler,
        Clock clock,
        JarvisLog log,
        BooleanSupplier running,
        ChatSubmitter submitter
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
        this.serverScheduler = Objects.requireNonNull(
            serverScheduler,
            "serverScheduler"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
        this.log = Objects.requireNonNull(log, "log");
        this.running = Objects.requireNonNull(running, "running");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
    }

    boolean inFlight() {
        return inFlight.get();
    }

    void clearActor(UUID requesterUuid) {
        ambientTracker.clearActor(requesterUuid);
    }

    CompletionStage<Boolean> consider(
        UUID requesterUuid,
        String requesterName,
        String text
    ) {
        Objects.requireNonNull(requesterUuid, "requesterUuid");
        Objects.requireNonNull(requesterName, "requesterName");
        Objects.requireNonNull(text, "text");

        if (!running.getAsBoolean()) {
            return CompletableFuture.completedFuture(false);
        }
        if (!platform.isServerThread()) {
            return CompletableFuture.completedFuture(false);
        }

        JarvisConfig.Interaction interaction =
            configManager.current().interaction();
        if (
            interaction.mode()
                != JarvisConfig.InteractionMode.ACTIVE
        ) {
            return CompletableFuture.completedFuture(false);
        }

        PlayerIdentity current = platform
            .interactionPlayer(requesterUuid)
            .orElse(null);
        if (
            current == null
                || !interactions.isAuthorized(current)
                || sessions.activeSession(requesterUuid).isPresent()
        ) {
            logIgnored(
                requesterUuid,
                "ACTOR_INELIGIBLE",
                null,
                null
            );
            return CompletableFuture.completedFuture(false);
        }

        Instant now = clock.instant();
        ambientTracker.record(
            requesterUuid,
            requesterName,
            text,
            now
        );

        synchronized (this) {
            if (now.isBefore(cooldownUntil)) {
                logIgnored(
                    requesterUuid,
                    "COOLDOWN",
                    null,
                    null
                );
                return CompletableFuture.completedFuture(false);
            }
            if (now.isBefore(classificationAfter)) {
                logIgnored(
                    requesterUuid,
                    "CLASSIFICATION_INTERVAL",
                    null,
                    null
                );
                return CompletableFuture.completedFuture(false);
            }
            classificationAfter =
                now.plus(CLASSIFICATION_INTERVAL);
        }

        if (!inFlight.compareAndSet(false, true)) {
            logIgnored(
                requesterUuid,
                "IN_FLIGHT_LIMIT",
                null,
                null
            );
            return CompletableFuture.completedFuture(false);
        }

        List<AmbientChatMessage> context =
            ambientTracker.snapshot(
                interaction.proactive().contextMessages()
            );

        log.debug(
            JarvisEvents.PROACTIVE_CANDIDATE,
            JarvisFields.of(
                "requesterUuid", requesterUuid,
                "contextMessages", context.size()
            )
        );

        CompletionStage<io.github.kardane.jarvisminecraft.common.brain.ai.JevClassification>
            classification;
        try {
            classification = brain.classifyProactive(
                context,
                now.plus(JdkJevClassifier.MAX_TIMEOUT)
            );
        } catch (RuntimeException failure) {
            inFlight.set(false);
            logFailure(
                requesterUuid,
                failureReason(failure)
            );
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> accepted =
            new CompletableFuture<>();
        classification.whenComplete((decision, failure) -> {
            if (failure != null) {
                inFlight.set(false);
                logFailure(
                    requesterUuid,
                    failureReason(failure)
                );
                accepted.complete(false);
                return;
            }
            if (
                decision == null
                    || !JdkJevClassifier.MODEL.equals(
                        decision.model()
                    )
            ) {
                inFlight.set(false);
                logFailure(
                    requesterUuid,
                    "JEV_INVALID_OUTPUT"
                );
                accepted.complete(false);
                return;
            }
            if (
                decision.engagement()
                    != JevEngagement.START_CONVERSATION
            ) {
                inFlight.set(false);
                logIgnored(
                    requesterUuid,
                    "JEV_IGNORE",
                    decision.engagementConfidence(),
                    null
                );
                accepted.complete(false);
                return;
            }

            scheduleActivation(
                requesterUuid,
                requesterName,
                context,
                decision.engagementConfidence(),
                accepted
            );
        });
        return accepted;
    }

    private void scheduleActivation(
        UUID requesterUuid,
        String requesterName,
        List<AmbientChatMessage> context,
        double engagementConfidence,
        CompletableFuture<Boolean> accepted
    ) {
        try {
            serverScheduler.submit(() -> {
                try {
                    accepted.complete(
                        activate(
                            requesterUuid,
                            requesterName,
                            context,
                            engagementConfidence
                        )
                    );
                } finally {
                    inFlight.set(false);
                }
                return CompletableFuture.completedFuture(null);
            });
        } catch (RuntimeException failure) {
            inFlight.set(false);
            logFailure(
                requesterUuid,
                "ACTIVATION_SCHEDULER_FAILED"
            );
            accepted.complete(false);
        }
    }

    private boolean activate(
        UUID requesterUuid,
        String requesterName,
        List<AmbientChatMessage> context,
        double engagementConfidence
    ) {
        if (!running.getAsBoolean()) {
            return false;
        }
        if (!platform.isServerThread()) {
            return false;
        }

        JarvisConfig.Interaction interaction =
            configManager.current().interaction();
        if (
            interaction.mode()
                != JarvisConfig.InteractionMode.ACTIVE
        ) {
            logIgnored(
                requesterUuid,
                "MODE_CHANGED",
                engagementConfidence,
                null
            );
            return false;
        }
        if (
            engagementConfidence
                < interaction.proactive()
                    .confidenceThreshold()
        ) {
            logIgnored(
                requesterUuid,
                "CONFIDENCE_BELOW_THRESHOLD",
                engagementConfidence,
                interaction.proactive().confidenceThreshold()
            );
            return false;
        }

        Instant now = clock.instant();
        synchronized (this) {
            if (now.isBefore(cooldownUntil)) {
                logIgnored(
                    requesterUuid,
                    "COOLDOWN",
                    engagementConfidence,
                    null
                );
                return false;
            }
        }

        PlayerIdentity current = platform
            .interactionPlayer(requesterUuid)
            .orElse(null);
        if (
            current == null
                || !interactions.isAuthorized(current)
                || sessions.activeSession(requesterUuid).isPresent()
        ) {
            logIgnored(
                requesterUuid,
                "ACTOR_INELIGIBLE",
                engagementConfidence,
                null
            );
            return false;
        }

        UUID sessionId = sessions.start(
            requesterUuid,
            Duration.ofSeconds(
                interaction.followUpSeconds()
            )
        );
        synchronized (this) {
            cooldownUntil = now.plusSeconds(
                interaction.proactive().cooldownSeconds()
            );
        }

        log.info(
            JarvisEvents.PROACTIVE_ACCEPTED,
            JarvisFields.of(
                "requesterUuid", requesterUuid,
                "sessionId", sessionId,
                "confidence", engagementConfidence
            )
        );

        String proactiveText =
            AmbientConversationTracker.promptContext(
                context
            );
        submitter.submit(
            requesterUuid,
            requesterName,
            sessionId,
            "PROACTIVE",
            proactiveText
        ).whenComplete((sent, failure) -> {
            if (
                failure != null
                    || !Boolean.TRUE.equals(sent)
            ) {
                logFailure(
                    requesterUuid,
                    failure == null
                        ? "REQUEST_REJECTED"
                        : failureReason(failure)
                );
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
        return true;
    }

    private void logIgnored(
        UUID requesterUuid,
        String reason,
        Double confidence,
        Double threshold
    ) {
        log.debug(
            JarvisEvents.PROACTIVE_IGNORED,
            JarvisFields.of(
                "requesterUuid", requesterUuid,
                "reason", reason,
                "confidence", confidence,
                "threshold", threshold
            )
        );
    }

    private void logFailure(
        UUID requesterUuid,
        String reason
    ) {
        log.warn(
            JarvisEvents.PROACTIVE_FAILED,
            JarvisFields.of(
                "requesterUuid", requesterUuid,
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
