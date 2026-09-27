package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.audit.AsyncJsonlAuditSink;
import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.JdkJevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevEngagement;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaStep;
import io.github.kardane.jarvisminecraft.common.brain.ai.OpenAiLunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.ReasoningPolicy;
import io.github.kardane.jarvisminecraft.common.chat.AmbientChatMessage;
import io.github.kardane.jarvisminecraft.common.chat.AmbientConversationTracker;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.chat.InteractionCoordinator;
import io.github.kardane.jarvisminecraft.common.chat.PlayerIdentity;
import io.github.kardane.jarvisminecraft.common.chat.StyledChatMessage;
import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
import io.github.kardane.jarvisminecraft.common.logging.JarvisEvents;
import io.github.kardane.jarvisminecraft.common.logging.JarvisFields;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.logging.NoOpJarvisLog;
import io.github.kardane.jarvisminecraft.common.platform.AdapterPlatformAccess;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.brain.Capability;
import io.github.kardane.jarvisminecraft.common.protocol.ToolArgumentCodec;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ExecutionPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ScheduledActionService;
import io.github.kardane.jarvisminecraft.common.runtime.SchedulingPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

public final class EmbeddedBrainGateway implements BrainGateway {
    private static final Duration PROACTIVE_CLASSIFICATION_INTERVAL =
        Duration.ofSeconds(1);
    private final EmbeddedBrain brain;
    private final ChatSessionManager sessions;
    private final InteractionCoordinator interactions;
    private final ConfigManager configManager;
    private final ProgressNotifier progressNotifier;
    private final AdapterPlatformAccess platform;
    private final ServerScheduler serverScheduler;
    private final Clock clock;
    private final LunaClient ownedLuna;
    private final AsyncJsonlAuditSink ownedAudit;
    private final ExecutorService ownedAiExecutor;
    private final JarvisLog log;
    private final AmbientConversationTracker ambientTracker =
        new AmbientConversationTracker();
    private final AtomicBoolean proactiveInFlight =
        new AtomicBoolean();

    private boolean started;
    private boolean stopped;
    private Instant proactiveCooldownUntil = Instant.EPOCH;
    private Instant proactiveClassificationAfter = Instant.EPOCH;
    private volatile AsyncJsonlAuditSink.Status lastAuditStatus;
    private volatile String lastAuditErrorCode;

    public EmbeddedBrainGateway(
        EmbeddedBrain brain,
        ChatSessionManager sessions,
        AdapterPlatformAccess platform,
        ServerScheduler serverScheduler,
        Clock clock
    ) {
        this(
            brain,
            sessions,
            new InteractionCoordinator(
                sessions,
                new ConfigManager(JarvisConfig::defaults)
            ),
            new ConfigManager(JarvisConfig::defaults),
            new ProgressNotifier(),
            platform,
            serverScheduler,
            clock,
            null,
            null,
            null,
            NoOpJarvisLog.INSTANCE
        );
    }

    public EmbeddedBrainGateway(
        EmbeddedBrain brain,
        ChatSessionManager sessions,
        InteractionCoordinator interactions,
        AdapterPlatformAccess platform,
        ServerScheduler serverScheduler,
        Clock clock
    ) {
        this(
            brain,
            sessions,
            interactions,
            new ConfigManager(JarvisConfig::defaults),
            new ProgressNotifier(),
            platform,
            serverScheduler,
            clock,
            null,
            null,
            null,
            NoOpJarvisLog.INSTANCE
        );
    }

    private EmbeddedBrainGateway(
        EmbeddedBrain brain,
        ChatSessionManager sessions,
        InteractionCoordinator interactions,
        ConfigManager configManager,
        ProgressNotifier progressNotifier,
        AdapterPlatformAccess platform,
        ServerScheduler serverScheduler,
        Clock clock,
        LunaClient ownedLuna,
        AsyncJsonlAuditSink ownedAudit,
        ExecutorService ownedAiExecutor,
        JarvisLog log
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
        this.progressNotifier = Objects.requireNonNull(
            progressNotifier,
            "progressNotifier"
        );
        this.platform = Objects.requireNonNull(platform, "platform");
        this.serverScheduler = Objects.requireNonNull(
            serverScheduler,
            "serverScheduler"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownedLuna = ownedLuna;
        this.ownedAudit = ownedAudit;
        this.ownedAiExecutor = ownedAiExecutor;
        this.log = Objects.requireNonNull(log, "log");
    }

    public static EmbeddedBrainGateway live(
        String serverId,
        List<Capability> capabilities,
        String openAiApiKey,
        String typesafeApiKey,
        Path auditDirectory,
        ChatSessionManager sessions,
        ToolRegistry registry,
        CommonRuntime commonRuntime,
        ServerScheduler serverScheduler,
        AdapterPlatformAccess platform,
        Clock clock
    ) {
        return live(
            serverId,
            capabilities,
            openAiApiKey,
            typesafeApiKey,
            auditDirectory,
            sessions,
            new InteractionCoordinator(
                sessions,
                new ConfigManager(JarvisConfig::defaults)
            ),
            registry,
            commonRuntime,
            serverScheduler,
            platform,
            clock
        );
    }

    public static EmbeddedBrainGateway live(
        String serverId,
        List<Capability> capabilities,
        String openAiApiKey,
        String typesafeApiKey,
        Path auditDirectory,
        ChatSessionManager sessions,
        InteractionCoordinator interactions,
        ToolRegistry registry,
        CommonRuntime commonRuntime,
        ServerScheduler serverScheduler,
        AdapterPlatformAccess platform,
        Clock clock
    ) {
        return live(
            serverId,
            capabilities,
            openAiApiKey,
            typesafeApiKey,
            auditDirectory,
            sessions,
            interactions,
            new ConfigManager(JarvisConfig::defaults),
            registry,
            commonRuntime,
            serverScheduler,
            platform,
            clock
        );
    }

    public static EmbeddedBrainGateway live(
        String serverId,
        List<Capability> capabilities,
        String openAiApiKey,
        String typesafeApiKey,
        Path auditDirectory,
        ChatSessionManager sessions,
        InteractionCoordinator interactions,
        ConfigManager configManager,
        ToolRegistry registry,
        CommonRuntime commonRuntime,
        ServerScheduler serverScheduler,
        AdapterPlatformAccess platform,
        Clock clock
    ) {
        return live(
            serverId,
            capabilities,
            openAiApiKey,
            typesafeApiKey,
            auditDirectory,
            sessions,
            interactions,
            configManager,
            registry,
            commonRuntime,
            serverScheduler,
            platform,
            clock,
            NoOpJarvisLog.INSTANCE
        );
    }

    public static EmbeddedBrainGateway live(
        String serverId,
        List<Capability> capabilities,
        String openAiApiKey,
        String typesafeApiKey,
        Path auditDirectory,
        ChatSessionManager sessions,
        InteractionCoordinator interactions,
        ConfigManager configManager,
        ToolRegistry registry,
        CommonRuntime commonRuntime,
        ServerScheduler serverScheduler,
        AdapterPlatformAccess platform,
        Clock clock,
        JarvisLog log
    ) {
        Objects.requireNonNull(configManager, "configManager");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(commonRuntime, "commonRuntime");

        ExecutorService aiExecutor = Executors.newFixedThreadPool(
            AiRequestScheduler.DEFAULT_MAX_CONCURRENT,
            runnable -> {
                Thread thread = new Thread(
                    runnable,
                    "jarvis-embedded-ai"
                );
                thread.setDaemon(true);
                return thread;
            }
        );

        AsyncJsonlAuditSink audit =
            new AsyncJsonlAuditSink(auditDirectory, clock);
        LunaClient luna = new OpenAiLunaClient(
            openAiApiKey,
            new ToolArgumentCodec()
        );

        EmbeddedBrain brain = new EmbeddedBrain(
            serverId,
            capabilities,
            sessions,
            new InMemoryConversationHistoryStore(),
            new AiRequestScheduler(aiExecutor),
            new JdkJevClassifier(
                typesafeApiKey,
                JdkJevClassifier.DEFAULT_ENDPOINT,
                HttpClient.newBuilder()
                    .executor(aiExecutor)
                    .build(),
                clock
            ),
            new DeterministicRoutePolicy(),
            luna,
            new ReasoningPolicy(configManager),
            new ExecutionPolicy(configManager),
            new SchedulingPolicy(configManager),
            new ScheduledActionService(clock),
            audit,
            commonRuntime.openRuntime(
                UUID.randomUUID(),
                serverId,
                registry.tools()
            ),
            clock,
            log
        );

        return new EmbeddedBrainGateway(
            brain,
            sessions,
            interactions,
            configManager,
            new ProgressNotifier(),
            platform,
            serverScheduler,
            clock,
            luna,
            audit,
            aiExecutor,
            log
        );
    }

    @Override
    public synchronized void start() {
        if (stopped) {
            throw new IllegalStateException(
                "Embedded Brain gateway cannot restart after stop."
            );
        }
        started = true;
        scheduleAuditHealthPoll();
    }

    @Override
    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        started = false;

        brain.stop();
        if (ownedLuna != null) {
            ownedLuna.close();
        }
        if (ownedAiExecutor != null) {
            ownedAiExecutor.shutdownNow();
        }
        if (ownedAudit != null) {
            ownedAudit.closeAsync();
        }
    }

    @Override
    public StatusSnapshot status() {
        JarvisConfig current = configManager.current();
        AiRequestScheduler.Snapshot scheduler =
            brain.schedulerSnapshot();
        AuditHealth auditHealth = ownedAudit == null
            ? AuditHealth.unavailable()
            : toAuditHealth(ownedAudit.health());

        String runtimeState;
        synchronized (this) {
            runtimeState = stopped
                ? "STOPPED"
                : started
                    ? "RUNNING"
                    : "STARTING";
        }

        return new StatusSnapshot(
            runtimeState,
            current.interaction().mode().name(),
            current.interaction().audience().mode().name(),
            current.execution().mode().name(),
            current.scheduling().enabled(),
            scheduler.queuedTotal(),
            scheduler.maxQueuedTotal(),
            scheduler.activeRequests(),
            scheduler.maxConcurrent(),
            proactiveInFlight.get(),
            auditHealth
        );
    }

    @Override
    public CompletionStage<Boolean> submitChat(
        UUID requesterUuid,
        String requesterName,
        UUID sessionId,
        String mode,
        String text
    ) {
        synchronized (this) {
            if (!started || stopped) {
                return CompletableFuture.completedFuture(false);
            }
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
        EmbeddedBrain.ChatRequest request = new EmbeddedBrain.ChatRequest(
            UUID.randomUUID(),
            requesterUuid,
            requesterName,
            sessionId,
            mode,
            text,
            now,
            now.plusMillis(RequestBudget.MAX_REQUEST_MILLIS),
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
            logRequestFailure(request, failure);
            return CompletableFuture.completedFuture(false);
        }

        ProgressNotifier.ProgressHandle progress =
            "PROACTIVE".equalsIgnoreCase(mode)
                ? progressNotifier.completedHandle()
                : scheduleProgress(
                    requesterUuid,
                    sessionId,
                    request.requestId(),
                    responseConfig
                );

        processing.whenComplete((reply, failure) -> {
            progress.complete();
            if (failure == null) {
                log.info(
                    JarvisEvents.REQUEST_COMPLETED,
                    JarvisFields.of(
                        "requestId", request.requestId(),
                        "sessionId", request.sessionId(),
                        "requesterUuid", request.requesterUuid(),
                        "origin", request.mode(),
                        "latencyMs", Math.max(
                            0L,
                            Duration.between(
                                request.receivedAt(),
                                clock.instant()
                            ).toMillis()
                        )
                    )
                );
                deliverReply(
                    requesterUuid,
                    sessionId,
                    reply,
                    responseConfig
                );
            } else {
                logRequestFailure(request, failure);
                deliverFailure(
                    requesterUuid,
                    sessionId,
                    failure,
                    responseConfig
                );
            }
        });

        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletionStage<Boolean> considerProactive(
        UUID requesterUuid,
        String requesterName,
        String text
    ) {
        Objects.requireNonNull(requesterUuid, "requesterUuid");
        Objects.requireNonNull(requesterName, "requesterName");
        Objects.requireNonNull(text, "text");

        synchronized (this) {
            if (!started || stopped) {
                return CompletableFuture.completedFuture(false);
            }
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
            if (now.isBefore(proactiveCooldownUntil)) {
                logProactiveIgnored(
                    requesterUuid,
                    "COOLDOWN",
                    null,
                    null
                );
                return CompletableFuture.completedFuture(false);
            }
            if (now.isBefore(proactiveClassificationAfter)) {
                logProactiveIgnored(
                    requesterUuid,
                    "CLASSIFICATION_INTERVAL",
                    null,
                    null
                );
                return CompletableFuture.completedFuture(false);
            }
            proactiveClassificationAfter =
                now.plus(PROACTIVE_CLASSIFICATION_INTERVAL);
        }

        if (!proactiveInFlight.compareAndSet(false, true)) {
            logProactiveIgnored(
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
            proactiveInFlight.set(false);
            logProactiveFailure(
                requesterUuid,
                proactiveFailureReason(failure)
            );
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> accepted =
            new CompletableFuture<>();
        classification.whenComplete((decision, failure) -> {
            if (failure != null) {
                proactiveInFlight.set(false);
                logProactiveFailure(
                    requesterUuid,
                    proactiveFailureReason(failure)
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
                proactiveInFlight.set(false);
                logProactiveFailure(
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
                proactiveInFlight.set(false);
                logProactiveIgnored(
                    requesterUuid,
                    "JEV_IGNORE",
                    decision.engagementConfidence(),
                    null
                );
                accepted.complete(false);
                return;
            }

            scheduleProactiveActivation(
                requesterUuid,
                requesterName,
                context,
                decision.engagementConfidence(),
                accepted
            );
        });
        return accepted;
    }

    private void scheduleProactiveActivation(
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
                        activateProactive(
                            requesterUuid,
                            requesterName,
                            context,
                            engagementConfidence
                        )
                    );
                } finally {
                    proactiveInFlight.set(false);
                }
                return CompletableFuture.completedFuture(null);
            });
        } catch (RuntimeException failure) {
            proactiveInFlight.set(false);
            accepted.complete(false);
        }
    }

    private boolean activateProactive(
        UUID requesterUuid,
        String requesterName,
        List<AmbientChatMessage> context,
        double engagementConfidence
    ) {
        synchronized (this) {
            if (!started || stopped) {
                return false;
            }
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
            logProactiveIgnored(
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
            logProactiveIgnored(
                requesterUuid,
                "CONFIDENCE_BELOW_THRESHOLD",
                engagementConfidence,
                interaction.proactive().confidenceThreshold()
            );
            return false;
        }

        Instant now = clock.instant();
        synchronized (this) {
            if (now.isBefore(proactiveCooldownUntil)) {
                logProactiveIgnored(
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
            logProactiveIgnored(
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
            proactiveCooldownUntil = now.plusSeconds(
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
        submitChat(
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
                logProactiveFailure(
                    requesterUuid,
                    failure == null
                        ? "REQUEST_REJECTED"
                        : proactiveFailureReason(failure)
                );
                sessions.end(requesterUuid, sessionId);
                brain.cancelSession(
                    requesterUuid,
                    sessionId
                );
            }
        });
        return true;
    }

    @Override
    public void cancelActor(UUID requesterUuid, CancelReason reason) {
        Objects.requireNonNull(requesterUuid, "requesterUuid");
        Objects.requireNonNull(reason, "reason");
        sessions.invalidate(requesterUuid);
        ambientTracker.clearActor(requesterUuid);
        brain.cancelActor(requesterUuid);
    }

    @Override
    public void cancelSession(
        UUID requesterUuid,
        UUID sessionId,
        CancelReason reason
    ) {
        Objects.requireNonNull(requesterUuid, "requesterUuid");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(reason, "reason");
        sessions.end(requesterUuid, sessionId);
        brain.cancelSession(requesterUuid, sessionId);
    }

    private void scheduleAuditHealthPoll() {
        if (
            ownedAudit == null
                || ownedAiExecutor == null
        ) {
            return;
        }
        int intervalSeconds =
            configManager.current()
                .logging()
                .healthIntervalSeconds();
        try {
            CompletableFuture.delayedExecutor(
                intervalSeconds,
                TimeUnit.SECONDS,
                ownedAiExecutor
            ).execute(() -> {
                synchronized (this) {
                    if (stopped) {
                        return;
                    }
                }
                reportAuditHealth();
                scheduleAuditHealthPoll();
            });
        } catch (RuntimeException ignored) {
            // Operational health reporting must not change runtime behavior.
        }
    }

    private void reportAuditHealth() {
        if (ownedAudit == null) {
            return;
        }
        AsyncJsonlAuditSink.Health health =
            ownedAudit.health();
        AsyncJsonlAuditSink.Status previous =
            lastAuditStatus;
        String previousError = lastAuditErrorCode;
        lastAuditStatus = health.status();
        lastAuditErrorCode = health.lastErrorCode();

        if (
            previous == health.status()
                && Objects.equals(
                    previousError,
                    health.lastErrorCode()
                )
        ) {
            return;
        }

        Map<String, Object> fields = JarvisFields.of(
            "queueDepth", health.queueDepth(),
            "maxQueue", health.maxQueue(),
            "rejectedRecords", health.rejectedRecords(),
            "writable", health.writable(),
            "lastErrorCode", health.lastErrorCode()
        );
        switch (health.status()) {
            case HEALTHY -> {
                if (
                    previous != null
                        && previous
                            != AsyncJsonlAuditSink.Status.HEALTHY
                ) {
                    log.info(
                        JarvisEvents.AUDIT_RECOVERED,
                        fields
                    );
                }
            }
            case DEGRADED -> log.warn(
                JarvisEvents.AUDIT_DEGRADED,
                fields
            );
            case UNHEALTHY -> log.error(
                JarvisEvents.AUDIT_UNHEALTHY,
                null,
                fields
            );
        }
    }

    private AuditHealth toAuditHealth(
        AsyncJsonlAuditSink.Health health
    ) {
        return new AuditHealth(
            health.status().name(),
            health.writable(),
            health.closed(),
            health.queueDepth(),
            health.maxQueue(),
            health.rejectedRecords(),
            health.lastSuccessfulWriteAt(),
            health.lastErrorAt(),
            health.lastErrorCode(),
            health.totalBytes(),
            health.fileCount()
        );
    }

    private void logProactiveIgnored(
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

    private void logProactiveFailure(
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

    private String proactiveFailureReason(
        Throwable failure
    ) {
        Throwable cause = unwrap(failure);
        if (
            cause instanceof ProtocolException protocol
                && protocol.code() == ErrorCode.TIMEOUT
        ) {
            return "JEV_TIMEOUT";
        }
        return "JEV_FAILED";
    }

    private void logRequestFailure(
        EmbeddedBrain.ChatRequest request,
        Throwable failure
    ) {
        Throwable cause = unwrap(failure);
        ErrorCode code = cause instanceof ProtocolException protocol
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
                "errorClass", cause.getClass().getSimpleName(),
                "latencyMs", Math.max(
                    0L,
                    Duration.between(
                        request.receivedAt(),
                        clock.instant()
                    ).toMillis()
                )
            )
        );
    }

    private void deliverReply(
        UUID requesterUuid,
        UUID sessionId,
        EmbeddedBrain.Reply reply,
        JarvisConfig.Response responseConfig
    ) {
        scheduleDelivery(() -> {
            if (
                !isInteractionAuthorized(requesterUuid)
                    || !sessions.isActive(requesterUuid, sessionId)
            ) {
                return;
            }

            platform.sendPublicStyled(
                styled(responseConfig, reply.text())
            );
            playResponseSound(
                requesterUuid,
                responseConfig
            );

            if (reply.sessionState() == LunaStep.SessionState.END) {
                sessions.end(requesterUuid, sessionId);
                brain.cancelSession(requesterUuid, sessionId);
            }
        });
    }

    private void deliverFailure(
        UUID requesterUuid,
        UUID sessionId,
        Throwable failure,
        JarvisConfig.Response responseConfig
    ) {
        Throwable cause = unwrap(failure);
        ErrorCode code = cause instanceof ProtocolException protocol
            ? protocol.code()
            : ErrorCode.INTERNAL;

        if (code == ErrorCode.UNAUTHORIZED || code == ErrorCode.CANCELLED) {
            return;
        }

        String text = safeErrorText(code);
        scheduleDelivery(() -> {
            if (
                isInteractionAuthorized(requesterUuid)
                    && sessions.isActive(requesterUuid, sessionId)
            ) {
                platform.sendPublicStyled(
                    styled(responseConfig, text)
                );
                playResponseSound(
                    requesterUuid,
                    responseConfig
                );
            }
        });
    }

    private ProgressNotifier.ProgressHandle scheduleProgress(
        UUID requesterUuid,
        UUID sessionId,
        UUID requestId,
        JarvisConfig.Response responseConfig
    ) {
        JarvisConfig.WaitingMessage waiting =
            responseConfig.waitingMessage();
        if (!waiting.enabled()) {
            return progressNotifier.completedHandle();
        }

        int index = Math.floorMod(
            requestId.hashCode(),
            waiting.messages().size()
        );
        String progressText = waiting.messages().get(index);

        return progressNotifier.schedule(
            Duration.ofMillis(waiting.thresholdMillis()),
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
                    styled(responseConfig, progressText)
                );
            })
        );
    }

    private StyledChatMessage styled(
        JarvisConfig.Response responseConfig,
        String body
    ) {
        return StyledChatMessage.fromLegacyPrefix(
            responseConfig.prefix(),
            body
        );
    }

    private void playResponseSound(
        UUID requesterUuid,
        JarvisConfig.Response responseConfig
    ) {
        JarvisConfig.Sound sound = responseConfig.sound();
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

    private boolean isInteractionAuthorized(UUID requesterUuid) {
        return platform.interactionPlayer(requesterUuid)
            .map(interactions::isAuthorized)
            .orElse(false);
    }

    private void scheduleDelivery(Runnable delivery) {
        synchronized (this) {
            if (!started || stopped) {
                return;
            }
        }
        try {
            serverScheduler.submit(() -> {
                synchronized (this) {
                    if (!started || stopped) {
                        return CompletableFuture.completedFuture(null);
                    }
                }
                delivery.run();
                return CompletableFuture.completedFuture(null);
            });
        } catch (RuntimeException ignored) {
            // Server shutdown or scheduler rejection: do not retry delivery.
        }
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (
            current instanceof CompletionException
                && current.getCause() != null
        ) {
            current = current.getCause();
        }
        return current;
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
            case INVALID_ARGUMENT, AMBIGUOUS_TARGET, NOT_FOUND ->
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
