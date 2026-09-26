package io.github.kardane.jarvisminecraft.common;

import io.github.kardane.jarvisminecraft.common.audit.AsyncJsonlAuditSink;
import io.github.kardane.jarvisminecraft.common.brain.AiRequestScheduler;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrain;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.InMemoryConversationHistoryStore;
import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.JdkJevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevCategory;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassification;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevInput;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaStep;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaTurnInput;
import io.github.kardane.jarvisminecraft.common.brain.ai.OpenAiLunaClient;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.platform.StandardPlatformAccess;
import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;
import io.github.kardane.jarvisminecraft.common.brain.Capability;
import io.github.kardane.jarvisminecraft.common.protocol.ToolArgumentCodec;
import io.github.kardane.jarvisminecraft.common.runtime.AuditSink;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.tools.StandardMinecraftToolService;
import io.github.kardane.jarvisminecraft.common.tools.StandardMinecraftTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class EmbeddedBrainLiveVerificationMain {
    private static final String SERVER_ID = "live-acceptance-server";
    private static final Clock CLOCK = Clock.systemUTC();

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("Starting Embedded Brain Phase E11 Live Verification");
        System.out.println("==================================================");

        ApiKeys keys = loadApiKeys();
        System.out.println("API Keys configured safely: OpenAI=[CONFIGURED], TypeSafe=[CONFIGURED]");

        Path auditDir = Files.createTempDirectory("jarvis-audit-e11-live-");
        System.out.println("Audit directory: " + auditDir);

        LivePlatform platform = new LivePlatform();
        ToolRegistry registry = new ToolRegistry();
        StandardMinecraftToolService toolService = new StandardMinecraftToolService(
            platform,
            CLOCK,
            "LivePlatform",
            "paper",
            1000L
        );
        toolService.register(registry);

        CommonRuntime runtime = new CommonRuntime(
            registry,
            directScheduler(),
            ignored -> true,
            CLOCK
        );

        ChatSessionManager sessions = new ChatSessionManager(CLOCK);
        InMemoryConversationHistoryStore history = new InMemoryConversationHistoryStore();
        AiRequestScheduler scheduler = new AiRequestScheduler(Runnable::run);

        ToolArgumentCodec argumentCodec = new ToolArgumentCodec();
        JdkJevClassifier rawJev = new JdkJevClassifier(keys.typesafeApiKey());
        OpenAiLunaClient rawLuna = new OpenAiLunaClient(keys.openaiApiKey(), argumentCodec);

        LoggingJevClassifier jev = new LoggingJevClassifier(rawJev);
        LoggingLunaClient luna = new LoggingLunaClient(rawLuna);

        AsyncJsonlAuditSink auditSink = new AsyncJsonlAuditSink(auditDir, CLOCK);
        DeterministicRoutePolicy routePolicy = new DeterministicRoutePolicy();

        CommonRuntime.ExecutionRuntime executionRuntime = runtime.openRuntime(
            UUID.randomUUID(),
            SERVER_ID,
            registry.tools()
        );

        EmbeddedBrain brain = new EmbeddedBrain(
            SERVER_ID,
            StandardMinecraftTools.capabilities("LivePlatform", "1.21.8"),
            sessions,
            history,
            scheduler,
            jev,
            routePolicy,
            luna,
            auditSink,
            executionRuntime,
            CLOCK
        );

        EmbeddedBrainGateway gateway = new EmbeddedBrainGateway(
            brain,
            sessions,
            platform,
            directScheduler(),
            CLOCK
        );
        gateway.start();

        try {
            System.out.println("\n--- [E11 Scenario 1: Server Status Query] ---");
            runScenario1(gateway, sessions, platform, jev, luna);

            System.out.println("\n--- [E11 Scenario 2: Self Teleport Action] ---");
            runScenario2(gateway, sessions, platform, jev, luna);

            gateway.stop();
            auditSink.closeAsync().toCompletableFuture().join();

            System.out.println("\n--- [Audit Verification] ---");
            verifyAuditOutput(auditDir);

            System.out.println("\n==================================================");
            System.out.println("Phase E11 Live Verification SUCCESSFUL!");
            System.out.println("==================================================");
        } finally {
            gateway.stop();
            auditSink.closeAsync().toCompletableFuture().join();
        }
    }

    private static void runScenario1(
        EmbeddedBrainGateway gateway,
        ChatSessionManager sessions,
        LivePlatform platform,
        LoggingJevClassifier jev,
        LoggingLunaClient luna
    ) {
        UUID actor = UUID.fromString("21000000-0000-4000-8000-000000000001");
        ChatSessionManager.Decision decision = sessions.accept(actor, true, "자비스 서버 상태 알려줘");
        require(decision.kind() == ChatSessionManager.Kind.FORWARD, "Scenario 1 session not started");
        UUID sessionId = decision.sessionId();

        platform.clearMessages();
        jev.reset();
        luna.reset();

        Instant start = Instant.now();
        System.out.println("Submitting Scenario 1: '자비스 서버 상태 알려줘'");
        boolean accepted = gateway.submitChat(
            actor,
            "Operator",
            sessionId,
            "DIRECT",
            "자비스 서버 상태 알려줘"
        ).toCompletableFuture().join();
        require(accepted, "Scenario 1 chat submission rejected");

        waitForMessages(platform, 1, Duration.ofSeconds(30));
        Duration elapsed = Duration.between(start, Instant.now());
        System.out.println("Scenario 1 finished in " + elapsed.toMillis() + " ms");

        JevClassification classification = jev.lastClassification();
        require(classification != null, "Jev was not called in Scenario 1");
        System.out.println("Jev classified: " + classification.category() + " (conf=" + classification.confidence() + ")");
        require(
            classification.category() == JevCategory.SERVER_QUERY,
            "Expected SERVER_QUERY but got " + classification.category()
        );

        List<LunaStep> steps = luna.recordedSteps();
        require(!steps.isEmpty(), "Luna was not called in Scenario 1");
        boolean calledGetServerStatus = steps.stream().anyMatch(step -> {
            if (step instanceof LunaStep.Tools tools) {
                return tools.calls().stream().anyMatch(call -> call.tool() == ToolName.GET_SERVER_STATUS);
            }
            return false;
        });
        require(calledGetServerStatus, "Luna did not propose get_server_status");
        System.out.println("Luna proposed get_server_status tool call as expected");

        String finalReply = platform.lastMessage();
        System.out.println("Final reply delivered to user: " + finalReply);
        require(finalReply != null && !finalReply.isBlank(), "No reply message delivered to user");
    }

    private static void runScenario2(
        EmbeddedBrainGateway gateway,
        ChatSessionManager sessions,
        LivePlatform platform,
        LoggingJevClassifier jev,
        LoggingLunaClient luna
    ) {
        UUID actor = UUID.fromString("21000000-0000-4000-8000-000000000001");
        ChatSessionManager.Decision decision = sessions.accept(actor, true, "자비스 나를 Steve한테 보내줘");
        require(decision.kind() == ChatSessionManager.Kind.FORWARD, "Scenario 2 session not started");
        UUID sessionId = decision.sessionId();

        platform.clearMessages();
        platform.resetTeleportCount();
        jev.reset();
        luna.reset();

        Instant start = Instant.now();
        System.out.println("Submitting Scenario 2: '자비스 나를 Steve한테 보내줘'");
        boolean accepted = gateway.submitChat(
            actor,
            "Operator",
            sessionId,
            "DIRECT",
            "자비스 나를 Steve한테 보내줘"
        ).toCompletableFuture().join();
        require(accepted, "Scenario 2 chat submission rejected");

        waitForMessages(platform, 1, Duration.ofSeconds(30));
        Duration elapsed = Duration.between(start, Instant.now());
        System.out.println("Scenario 2 finished in " + elapsed.toMillis() + " ms");

        JevClassification classification = jev.lastClassification();
        require(classification != null, "Jev was not called in Scenario 2");
        System.out.println("Jev classified: " + classification.category() + " (conf=" + classification.confidence() + ")");
        require(
            classification.category() == JevCategory.ACTION_REQUEST,
            "Expected ACTION_REQUEST but got " + classification.category()
        );

        List<LunaStep> steps = luna.recordedSteps();
        require(!steps.isEmpty(), "Luna was not called in Scenario 2");
        boolean calledTeleport = steps.stream().anyMatch(step -> {
            if (step instanceof LunaStep.Tools tools) {
                return tools.calls().stream().anyMatch(call -> call.tool() == ToolName.TELEPORT_STAFF);
            }
            return false;
        });
        require(calledTeleport, "Luna did not propose teleport_staff");
        System.out.println("Luna proposed teleport_staff tool call as expected");

        require(platform.teleportCount() == 1, "Platform teleportRequesterTo was not called exactly once");
        System.out.println("Platform executed teleport to target Steve");

        String finalReply = platform.lastMessage();
        System.out.println("Final reply delivered to user: " + finalReply);
        require(finalReply != null && !finalReply.isBlank(), "No reply message delivered to user");
    }

    private static void verifyAuditOutput(Path auditDir) throws IOException {
        List<Path> files;
        try (var stream = Files.list(auditDir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList();
        }
        require(!files.isEmpty(), "No audit JSONL files found in " + auditDir);
        for (Path file : files) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            require(!content.contains("sk-"), "Found unmasked API key pattern in audit file");
            require(content.contains("PRE_EXECUTION"), "Audit file does not contain PRE_EXECUTION event for state-changing tool");
            require(content.contains("get_server_status"), "Audit file does not contain get_server_status event");
            require(content.contains("teleport_staff"), "Audit file does not contain teleport_staff event");
        }
        System.out.println("Audit records verified: correct events present, secret masking confirmed.");
    }

    private static void waitForMessages(LivePlatform platform, int expectedCount, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (platform.messages().size() >= expectedCount) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Timed out waiting for " + expectedCount + " messages. Got: " + platform.messages().size());
    }

    private static ApiKeys loadApiKeys() {
        String openai = System.getenv("OPENAI_API_KEY");
        String typesafe = System.getenv("TYPESAFE_API_KEY");

        if (openai == null || typesafe == null || openai.isBlank() || typesafe.isBlank()) {
            Path repoRoot = Path.of(System.getProperty("jarvis.repoRoot", "."));
            Path[] candidateFiles = new Path[] {
                repoRoot.resolve("config/.env.local"),
                repoRoot.resolve("config/.env.acceptance.local"),
                Path.of("config/.env.local"),
                Path.of("config/.env.acceptance.local")
            };
            for (Path candidate : candidateFiles) {
                if (Files.isRegularFile(candidate)) {
                    try {
                        List<String> lines = Files.readAllLines(candidate, StandardCharsets.UTF_8);
                        for (String line : lines) {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("#") || !trimmed.contains("=")) {
                                continue;
                            }
                            String[] parts = trimmed.split("=", 2);
                            String key = parts[0].trim();
                            String val = parts[1].trim();
                            if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                                if (val.length() >= 2) {
                                    val = val.substring(1, val.length() - 1).trim();
                                }
                            }
                            if ("OPENAI_API_KEY".equals(key) && (openai == null || openai.isBlank())) {
                                openai = val;
                            }
                            if ("TYPESAFE_API_KEY".equals(key) && (typesafe == null || typesafe.isBlank())) {
                                typesafe = val;
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        require(openai != null && !openai.isBlank(), "OPENAI_API_KEY is not configured");
        require(typesafe != null && !typesafe.isBlank(), "TYPESAFE_API_KEY is not configured");
        return new ApiKeys(openai, typesafe);
    }

    private record ApiKeys(String openaiApiKey, String typesafeApiKey) {}

    private static ServerScheduler directScheduler() {
        return new ServerScheduler() {
            @Override
            public <T> CompletionStage<T> submit(java.util.function.Supplier<CompletionStage<T>> task) {
                try {
                    return task.get();
                } catch (RuntimeException failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class LoggingJevClassifier implements JevClassifier {
        private final JevClassifier delegate;
        private final AtomicReference<JevClassification> last = new AtomicReference<>();

        private LoggingJevClassifier(JevClassifier delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public CompletionStage<JevClassification> classify(JevInput input, Instant deadlineAt) {
            Instant start = Instant.now();
            return delegate.classify(input, deadlineAt).whenComplete((result, failure) -> {
                long duration = Duration.between(start, Instant.now()).toMillis();
                if (failure != null) {
                    System.out.println("  [Jev HTTP ERROR] Call took " + duration + " ms -> failure: " + failure.getMessage());
                    if (failure.getCause() != null) {
                        System.out.println("  [Jev HTTP ERROR cause] " + failure.getCause().getMessage());
                    }
                } else {
                    System.out.println("  [Jev HTTP] Call took " + duration + " ms -> category="
                        + result.category() + ", confidence=" + result.confidence());
                    last.set(result);
                }
            });
        }

        public JevClassification lastClassification() {
            return last.get();
        }

        public void reset() {
            last.set(null);
        }
    }

    private static final class LoggingLunaClient implements LunaClient {
        private final LunaClient delegate;
        private final List<LunaStep> steps = new ArrayList<>();

        private LoggingLunaClient(LunaClient delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public String modelId() {
            return delegate.modelId();
        }

        @Override
        public CompletionStage<LunaStep> next(
            LunaTurnInput input,
            DeterministicRoutePolicy.RoutingDecision routing
        ) {
            Instant start = Instant.now();
            return delegate.next(input, routing).thenApply(step -> {
                long duration = Duration.between(start, Instant.now()).toMillis();
                if (step instanceof LunaStep.Tools tools) {
                    System.out.println("  [Luna HTTP] Turn took " + duration + " ms -> Tools proposed (" + tools.calls().size() + "): "
                        + tools.calls().stream().map(c -> c.tool().wireName()).toList());
                } else if (step instanceof LunaStep.Final f) {
                    System.out.println("  [Luna HTTP] Turn took " + duration + " ms -> Final step: " + f.text());
                }
                synchronized (steps) {
                    steps.add(step);
                }
                return step;
            });
        }

        @Override
        public void clear(UUID requestId) {
            delegate.clear(requestId);
        }

        public List<LunaStep> recordedSteps() {
            synchronized (steps) {
                return new ArrayList<>(steps);
            }
        }

        public void reset() {
            synchronized (steps) {
                steps.clear();
            }
        }
    }

    private static final class LivePlatform implements StandardPlatformAccess {
        private final UUID operatorUuid = UUID.fromString("21000000-0000-4000-8000-000000000001");
        private final UUID steveUuid = UUID.fromString("22000000-0000-4000-8000-000000000099");
        private final List<String> sentMessages = new ArrayList<>();
        private final AtomicInteger teleports = new AtomicInteger();

        private final PlayerSnapshot operatorSnapshot = new PlayerSnapshot(
            operatorUuid,
            "Operator",
            true,
            new LocationSnapshot("world", 0.0, 64.0, 0.0, 0.0, 0.0)
        );

        private final PlayerSnapshot steveSnapshot = new PlayerSnapshot(
            steveUuid,
            "Steve",
            true,
            new LocationSnapshot("world", 100.0, 64.0, 200.0, 45.0, 10.0)
        );

        @Override
        public boolean isServerThread() {
            return true;
        }

        @Override
        public boolean isOnlineOperator(UUID playerUuid) {
            return true;
        }

        @Override
        public void sendPrivatePlain(UUID requesterUuid, String text) {
            synchronized (sentMessages) {
                sentMessages.add(text);
            }
            System.out.println("  [Platform Deliver -> " + requesterUuid + "] " + text);
        }

        @Override
        public Optional<PlayerSnapshot> findOnlinePlayer(UUID playerUuid) {
            if (operatorUuid.equals(playerUuid)) return Optional.of(operatorSnapshot);
            if (steveUuid.equals(playerUuid)) return Optional.of(steveSnapshot);
            return Optional.empty();
        }

        @Override
        public Optional<PlayerSnapshot> findOnlinePlayerExact(String exactName) {
            if ("Operator".equalsIgnoreCase(exactName)) return Optional.of(operatorSnapshot);
            if ("Steve".equalsIgnoreCase(exactName)) return Optional.of(steveSnapshot);
            return Optional.empty();
        }

        @Override
        public List<PlayerSnapshot> onlinePlayers() {
            return List.of(operatorSnapshot, steveSnapshot);
        }

        @Override
        public Optional<WorldSnapshot> findLoadedWorld(String worldId) {
            if ("world".equals(worldId)) {
                return Optional.of(new WorldSnapshot("world", "minecraft:overworld", 2, "normal", 6000L));
            }
            return Optional.empty();
        }

        @Override
        public ServerStatusSnapshot serverStatus() {
            return new ServerStatusSnapshot(
                20.0,
                14.2,
                2,
                450,
                1024L * 1024L * 512L,
                1024L * 1024L * 2048L
            );
        }

        @Override
        public List<NearbyPlayerSnapshot> nearbyPlayers(LocationSnapshot center, double radius, int limit) {
            return List.of(new NearbyPlayerSnapshot(steveSnapshot, 223.6));
        }

        @Override
        public CompletionStage<TeleportSnapshot> teleportRequesterTo(UUID requesterUuid, UUID targetPlayerUuid) {
            teleports.incrementAndGet();
            return CompletableFuture.completedFuture(
                new TeleportSnapshot(
                    requesterUuid,
                    targetPlayerUuid,
                    "world",
                    "world",
                    true
                )
            );
        }

        public List<String> messages() {
            synchronized (sentMessages) {
                return new ArrayList<>(sentMessages);
            }
        }

        public String lastMessage() {
            synchronized (sentMessages) {
                if (sentMessages.isEmpty()) return null;
                return sentMessages.get(sentMessages.size() - 1);
            }
        }

        public void clearMessages() {
            synchronized (sentMessages) {
                sentMessages.clear();
            }
        }

        public int teleportCount() {
            return teleports.get();
        }

        public void resetTeleportCount() {
            teleports.set(0);
        }
    }
}
