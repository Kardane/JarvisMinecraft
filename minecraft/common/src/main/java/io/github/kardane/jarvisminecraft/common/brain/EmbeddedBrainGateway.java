package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.chat.InteractionCoordinator;
import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.logging.NoOpJarvisLog;
import io.github.kardane.jarvisminecraft.common.platform.AdapterPlatformAccess;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;

public final class EmbeddedBrainGateway implements BrainGateway {
    private final EmbeddedBrain brain;
    private final ChatSessionManager sessions;
    private final ConfigManager configManager;
    private final EmbeddedBrainBootstrap.LiveRuntime ownedRuntime;
    private final GatewayRequestCoordinator requests;
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
        Objects.requireNonNull(interactions, "interactions");
        this.configManager = Objects.requireNonNull(
            configManager,
            "configManager"
        );
        if (
            interactions.configManager()
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
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(
            serverScheduler,
            "serverScheduler"
        );
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(log, "log");
        this.ownedRuntime = ownedRuntime;

        GatewayReplyPresenter presenter =
            new GatewayReplyPresenter(
                brain,
                sessions,
                interactions,
                progressNotifier,
                platform,
                serverScheduler,
                this::isRunning
            );
        this.requests = new GatewayRequestCoordinator(
            brain,
            sessions,
            interactions,
            configManager,
            platform,
            clock,
            log,
            presenter,
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
                requests::submit
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
        return requests.submit(
            requesterUuid,
            requesterName,
            sessionId,
            mode,
            text
        );
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
}
