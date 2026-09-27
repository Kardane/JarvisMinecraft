package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.audit.AsyncJsonlAuditSink;
import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.JdkJevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaStep;
import io.github.kardane.jarvisminecraft.common.brain.ai.OpenAiLunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.ReasoningPolicy;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.chat.InteractionCoordinator;
import io.github.kardane.jarvisminecraft.common.chat.PlayerIdentity;
import io.github.kardane.jarvisminecraft.common.chat.StyledChatMessage;
import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
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

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

public final class EmbeddedBrainGateway implements BrainGateway {
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
            new ConfigManager(JarvisConfig::defaults),
            new ProgressNotifier(),
            platform,
            serverScheduler,
            clock,
            null,
            null,
            null
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
            null
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
        ExecutorService ownedAiExecutor
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
        Objects.requireNonNull(configManager, "configManager");
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
            clock
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
            aiExecutor
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

        JarvisConfig.Response responseConfig =
            configManager.current().response();

        final CompletionStage<EmbeddedBrain.Reply> processing;
        try {
            processing = brain.submit(request);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(false);
        }

        ProgressNotifier.ProgressHandle progress =
            scheduleProgress(
                requesterUuid,
                sessionId,
                request.requestId(),
                responseConfig
            );

        processing.whenComplete((reply, failure) -> {
            progress.complete();
            if (failure == null) {
                deliverReply(
                    requesterUuid,
                    sessionId,
                    reply,
                    responseConfig
                );
            } else {
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
    public void cancelActor(UUID requesterUuid, CancelReason reason) {
        Objects.requireNonNull(requesterUuid, "requesterUuid");
        Objects.requireNonNull(reason, "reason");
        sessions.invalidate(requesterUuid);
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
