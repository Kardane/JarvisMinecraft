package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.chat.InteractionCoordinator;
import io.github.kardane.jarvisminecraft.common.chat.PlayerIdentity;
import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
import io.github.kardane.jarvisminecraft.common.logging.JarvisEvents;
import io.github.kardane.jarvisminecraft.common.logging.JarvisFields;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.logging.NoOpJarvisLog;
import io.github.kardane.jarvisminecraft.common.platform.AdapterPlatformAccess;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.brain.BrainAsync.unwrap;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

public final class EmbeddedBrainGateway implements BrainGateway {
    private final EmbeddedBrain brain;
    private final ChatSessionManager sessions;
    private final InteractionCoordinator interactions;
    private final ConfigManager configManager;
    private final AdapterPlatformAccess platform;
    private final Clock clock;
    private final EmbeddedBrainBootstrap.LiveRuntime ownedRuntime;
    private final JarvisLog log;
    private final GatewayReplyPresenter presenter;
    private final ProactiveInteractionController proactive;
    private final GatewayAuditHealthMonitor auditHealth;

    private boolean started;
    private boolean stopped;

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
            platform,
            serverScheduler,
            clock
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
            interactions.configManager(),
            new ProgressNotifier(),
            platform,
            serverScheduler,
            clock,
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
        EmbeddedBrainBootstrap.LiveRuntime ownedRuntime,
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
        if (
            this.interactions.configManager()
                != this.configManager
        ) {
            throw new IllegalArgumentException(
                "InteractionCoordinator and EmbeddedBrainGateway must share the same ConfigManager instance."
            );
        }

        Objects.requireNonNull(
            progressNotifier,
            "progressNotifier"
        );
        this.platform = Objects.requireNonNull(
            platform,
            "platform"
        );
        Objects.requireNonNull(
            serverScheduler,
            "serverScheduler"
        );
        this.clock = Objects.requireNonNull(
            clock,
            "clock"
        );
        this.ownedRuntime = ownedRuntime;
        this.log = Objects.requireNonNull(log, "log");

        this.presenter = new GatewayReplyPresenter(
            brain,
            sessions,
            interactions,
            progressNotifier,
            platform,
            serverScheduler,
            this::isRunning
        );
        this.proactive =
            new ProactiveInteractionController(
                brain,
                sessions,
                interactions,
                configManager,
                platform,
                serverScheduler,
                clock,
                log,
                this::isRunning,
                this::submitChat
            );
        this.auditHealth =
            new GatewayAuditHealthMonitor(
                configManager,
                ownedRuntime == null
                    ? null
                    : ownedRuntime.audit(),
                ownedRuntime == null
                    ? null
                    : ownedRuntime.aiExecutor(),
                this::isRunning,
                log
            );
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
            interactions.configManager(),
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
        Objects.requireNonNull(
            interactions,
            "interactions"
        );
        Objects.requireNonNull(
            configManager,
            "configManager"
        );
        if (
            interactions.configManager()
                != configManager
        ) {
            throw new IllegalArgumentException(
                "InteractionCoordinator and Embedded Brain policies must share the same ConfigManager instance."
            );
        }
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(
            commonRuntime,
            "commonRuntime"
        );

        EmbeddedBrainBootstrap.LiveRuntime runtime =
            EmbeddedBrainBootstrap.create(
                serverId,
                capabilities,
                openAiApiKey,
                typesafeApiKey,
                auditDirectory,
                sessions,
                configManager,
                registry,
                commonRuntime,
                clock,
                log
            );

        try {
            return new EmbeddedBrainGateway(
                runtime.brain(),
                sessions,
                interactions,
                configManager,
                new ProgressNotifier(),
                platform,
                serverScheduler,
                clock,
                runtime,
                log
            );
        } catch (RuntimeException failure) {
            runtime.brain().stop();
            runtime.closeOwnedResources();
            throw failure;
        }
    }

    @Override
    public synchronized void start() {
        if (stopped) {
            throw new IllegalStateException(
                "Embedded Brain gateway cannot restart after stop."
            );
        }
        started = true;
        auditHealth.start();
    }

    @Override
    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        started = false;

        brain.stop();
        if (ownedRuntime != null) {
            ownedRuntime.closeOwnedResources();
        }
    }

    @Override
    public StatusSnapshot status() {
        JarvisConfig current =
            configManager.current();
        AiRequestScheduler.Snapshot scheduler =
            brain.schedulerSnapshot();

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
            current.interaction()
                .audience()
                .mode()
                .name(),
            current.execution().mode().name(),
            current.scheduling().enabled(),
            scheduler.queuedTotal(),
            scheduler.maxQueuedTotal(),
            scheduler.activeRequests(),
            scheduler.maxConcurrent(),
            proactive.inFlight(),
            auditHealth.snapshot()
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
        if (!isRunning()) {
            return CompletableFuture.completedFuture(
                false
            );
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
                || !interactions.isAuthorized(
                    currentPlayer
                )
        ) {
            return CompletableFuture.completedFuture(
                false
            );
        }
        if (
            !sessions.isActive(
                requesterUuid,
                sessionId
            )
        ) {
            return CompletableFuture.completedFuture(
                false
            );
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

        final CompletionStage<EmbeddedBrain.Reply>
            processing;
        try {
            processing = brain.submit(request);
        } catch (RuntimeException failure) {
            logRequestFailure(
                request,
                failure
            );
            return CompletableFuture.completedFuture(
                false
            );
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
            if (failure == null) {
                log.info(
                    JarvisEvents.REQUEST_COMPLETED,
                    JarvisFields.of(
                        "requestId",
                        request.requestId(),
                        "sessionId",
                        request.sessionId(),
                        "requesterUuid",
                        request.requesterUuid(),
                        "origin",
                        request.mode(),
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
                presenter.deliverReply(
                    requesterUuid,
                    sessionId,
                    reply,
                    responseConfig
                );
            } else {
                logRequestFailure(
                    request,
                    failure
                );
                presenter.deliverFailure(
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
        return proactive.consider(
            requesterUuid,
            requesterName,
            text
        );
    }

    @Override
    public void cancelActor(
        UUID requesterUuid,
        CancelReason reason
    ) {
        Objects.requireNonNull(
            requesterUuid,
            "requesterUuid"
        );
        Objects.requireNonNull(reason, "reason");

        sessions.invalidate(requesterUuid);
        proactive.clearActor(requesterUuid);
        brain.cancelActor(requesterUuid);
    }

    @Override
    public void cancelSession(
        UUID requesterUuid,
        UUID sessionId,
        CancelReason reason
    ) {
        Objects.requireNonNull(
            requesterUuid,
            "requesterUuid"
        );
        Objects.requireNonNull(
            sessionId,
            "sessionId"
        );
        Objects.requireNonNull(reason, "reason");

        sessions.end(
            requesterUuid,
            sessionId
        );
        brain.cancelSession(
            requesterUuid,
            sessionId
        );
    }

    private synchronized boolean isRunning() {
        return started && !stopped;
    }

    private void logRequestFailure(
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
                "requesterUuid",
                request.requesterUuid(),
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
