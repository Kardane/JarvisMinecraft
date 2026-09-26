package io.github.kardane.jarvisminecraft.common;

import io.github.kardane.jarvisminecraft.common.audit.AsyncJsonlAuditSink;
import io.github.kardane.jarvisminecraft.common.brain.AiRequestScheduler;
import io.github.kardane.jarvisminecraft.common.brain.ConversationEntry;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrain;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainSettings;
import io.github.kardane.jarvisminecraft.common.brain.InMemoryConversationHistoryStore;
import io.github.kardane.jarvisminecraft.common.brain.RequestBudget;
import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevCategory;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassification;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevInput;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaStep;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaTurnInput;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.platform.AdapterPlatformAccess;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.brain.Capability;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.NoArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.TeleportArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.TeleportData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.AuditSink;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class EmbeddedBrainVerificationMain {
    private static final Instant NOW = Instant.parse("2026-09-26T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SERVER_ID = "main";

    private EmbeddedBrainVerificationMain() {
    }

    public static void main(String[] args) throws Exception {
        commonRuntimeDirectInvocation();
        embeddedReadOnlyLoop();
        preAuditFailClosed();
        gatewayDeliveryAuthorityRecheck();
        gatewayCancellationOwnsSession();
        gatewayStopSuppressesLateDelivery();
        jsonlAuditContract();
        settingsContract();
        System.out.println("Embedded Brain E8-E10 verification OK");
    }

    private static void commonRuntimeDirectInvocation() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register(
            ToolName.GET_SERVER_STATUS,
            NoArguments.class,
            (context, arguments) -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    ToolResult.error(
                        ErrorCode.NOT_FOUND,
                        "fixture",
                        false,
                        NOW,
                        "FakePlatform"
                    )
                );
            }
        );

        CommonRuntime runtime = new CommonRuntime(
            registry,
            directScheduler(),
            ignored -> true,
            CLOCK
        );
        CommonRuntime.ExecutionRuntime execution = runtime.openRuntime(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
            SERVER_ID,
            EnumSet.of(ToolName.GET_SERVER_STATUS)
        );

        ToolResult result = execution.execute(
            new CommonRuntime.ToolInvocation(
                NOW,
                NOW.plusSeconds(5),
                UUID.fromString("20000000-0000-4000-8000-000000000001"),
                UUID.fromString("30000000-0000-4000-8000-000000000001"),
                UUID.fromString("40000000-0000-4000-8000-000000000001"),
                UUID.fromString("50000000-0000-4000-8000-000000000001"),
                null,
                ToolName.GET_SERVER_STATUS,
                new NoArguments()
            )
        ).toCompletableFuture().join();

        require(calls.get() == 1, "Direct ToolInvocation did not execute exactly once.");
        require(
            result.error().code() == ErrorCode.NOT_FOUND,
            "Direct ToolInvocation changed ToolResult."
        );
    }

    private static void embeddedReadOnlyLoop() {
        UUID actor = UUID.fromString("21000000-0000-4000-8000-000000000001");
        ChatSessionManager sessions = new ChatSessionManager(CLOCK);
        UUID sessionId = startSession(sessions, actor);

        ToolRegistry registry = new ToolRegistry();
        AtomicInteger handlerCalls = new AtomicInteger();
        registry.register(
            ToolName.GET_SERVER_STATUS,
            NoArguments.class,
            (context, arguments) -> {
                handlerCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    ToolResult.error(
                        ErrorCode.NOT_FOUND,
                        "fixture",
                        false,
                        NOW,
                        "FakePlatform"
                    )
                );
            }
        );

        CommonRuntime runtime = new CommonRuntime(
            registry,
            directScheduler(),
            ignored -> true,
            CLOCK
        );

        InMemoryConversationHistoryStore history =
            new InMemoryConversationHistoryStore();
        RecordingAudit audit = new RecordingAudit(true);
        SequenceLuna luna = new SequenceLuna(
            new LunaStep.Tools(
                List.of(
                    new LunaStep.ToolCall(
                        ToolName.GET_SERVER_STATUS,
                        new NoArguments()
                    )
                )
            ),
            new LunaStep.Final("서버 상태 확인 완료", LunaStep.SessionState.CONTINUE)
        );

        EmbeddedBrain brain = new EmbeddedBrain(
            SERVER_ID,
            capabilities(),
            sessions,
            history,
            new AiRequestScheduler(Runnable::run),
            classifier(JevCategory.SERVER_QUERY),
            new DeterministicRoutePolicy(),
            luna,
            audit,
            runtime.openRuntime(
                UUID.fromString("11000000-0000-4000-8000-000000000001"),
                SERVER_ID,
                registry.tools()
            ),
            CLOCK
        );

        UUID requestId = UUID.fromString("31000000-0000-4000-8000-000000000001");
        EmbeddedBrain.Reply reply = brain.submit(
            request(requestId, actor, sessionId, "자비스 서버 상태 알려줘")
        ).toCompletableFuture().join();

        require(
            "서버 상태 확인 완료".equals(reply.text()),
            "Embedded Brain did not return Luna final text."
        );
        require(handlerCalls.get() == 1, "Embedded Tool loop did not execute Tool exactly once.");
        require(luna.calls.get() == 2, "Embedded Tool loop did not perform the second model round.");
        require(
            luna.secondRoundSawToolResult,
            "Luna second round did not receive Tool result history."
        );
        require(
            audit.events.size() == 1
                && "ERROR".equals(audit.events.get(0).outcome()),
            "Read-only Tool should produce post-execution audit only."
        );

        List<ConversationEntry> entries = history.history(actor, sessionId);
        require(entries.size() == 3, "Embedded history must contain user/tool/assistant entries.");
        require(entries.get(1) instanceof ConversationEntry.ToolMessage, "Tool history entry missing.");
    }

    private static void preAuditFailClosed() {
        UUID actor = UUID.fromString("22000000-0000-4000-8000-000000000001");
        ChatSessionManager sessions = new ChatSessionManager(CLOCK);
        UUID sessionId = startSession(sessions, actor);
        UUID target = UUID.fromString("22000000-0000-4000-8000-000000000099");

        ToolRegistry registry = new ToolRegistry();
        AtomicInteger handlerCalls = new AtomicInteger();
        registry.register(
            ToolName.TELEPORT_STAFF,
            TeleportArguments.class,
            (context, arguments) -> {
                handlerCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    ToolResult.ok(
                        new TeleportData(
                            actor,
                            target,
                            "world",
                            "world",
                            true
                        ),
                        NOW,
                        "FakePlatform"
                    )
                );
            }
        );

        CommonRuntime runtime = new CommonRuntime(
            registry,
            directScheduler(),
            ignored -> true,
            CLOCK
        );
        RecordingAudit audit = new RecordingAudit(false);
        SequenceLuna luna = new SequenceLuna(
            new LunaStep.Tools(
                List.of(
                    new LunaStep.ToolCall(
                        ToolName.TELEPORT_STAFF,
                        new TeleportArguments(target)
                    )
                )
            )
        );

        EmbeddedBrain brain = new EmbeddedBrain(
            SERVER_ID,
            capabilities(),
            sessions,
            new InMemoryConversationHistoryStore(),
            new AiRequestScheduler(Runnable::run),
            classifier(JevCategory.ACTION_REQUEST),
            new DeterministicRoutePolicy(),
            luna,
            audit,
            runtime.openRuntime(
                UUID.fromString("12000000-0000-4000-8000-000000000001"),
                SERVER_ID,
                registry.tools()
            ),
            CLOCK
        );

        CompletionStage<EmbeddedBrain.Reply> result = brain.submit(
            request(
                UUID.fromString("32000000-0000-4000-8000-000000000001"),
                actor,
                sessionId,
                "자비스 나를 저 플레이어에게 보내줘"
            )
        );

        expectProtocolStage(ErrorCode.INTERNAL, result);
        require(handlerCalls.get() == 0, "State-changing Tool executed despite audit failure.");
        require(
            audit.events.size() == 1
                && "PRE_EXECUTION".equals(audit.events.get(0).outcome()),
            "State-changing Tool did not attempt pre-execution audit."
        );
    }

    private static void gatewayDeliveryAuthorityRecheck() {
        UUID actor = UUID.fromString("23000000-0000-4000-8000-000000000001");
        ChatSessionManager sessions = new ChatSessionManager(CLOCK);
        UUID sessionId = startSession(sessions, actor);

        ToolRegistry registry = new ToolRegistry();
        CommonRuntime runtime = new CommonRuntime(
            registry,
            directScheduler(),
            ignored -> true,
            CLOCK
        );

        CompletableFuture<LunaStep> gate = new CompletableFuture<>();
        GatedLuna luna = new GatedLuna(gate);
        EmbeddedBrain brain = new EmbeddedBrain(
            SERVER_ID,
            capabilities(),
            sessions,
            new InMemoryConversationHistoryStore(),
            new AiRequestScheduler(Runnable::run),
            classifier(JevCategory.GENERAL),
            new DeterministicRoutePolicy(),
            luna,
            AuditSink.noOp(),
            runtime.openRuntime(
                UUID.fromString("13000000-0000-4000-8000-000000000001"),
                SERVER_ID,
                registry.tools()
            ),
            CLOCK
        );

        FakePlatform platform = new FakePlatform(true);
        EmbeddedBrainGateway gateway = new EmbeddedBrainGateway(
            brain,
            sessions,
            platform,
            directScheduler(),
            CLOCK
        );
        gateway.start();

        boolean accepted = gateway.submitChat(
            actor,
            "Operator",
            sessionId,
            "DIRECT",
            "자비스 안녕"
        ).toCompletableFuture().join();
        require(accepted, "Embedded gateway did not accept a valid request.");

        platform.operator = false;
        gate.complete(
            new LunaStep.Final(
                "이 메시지는 전달되면 안 됨",
                LunaStep.SessionState.CONTINUE
            )
        );

        require(
            platform.messages.isEmpty(),
            "Gateway delivered a stale response after OP authority was revoked."
        );
        gateway.stop();
    }

    private static void gatewayCancellationOwnsSession() {
        UUID actor = UUID.fromString("23100000-0000-4000-8000-000000000001");
        ChatSessionManager sessions = new ChatSessionManager(CLOCK);
        UUID sessionId = startSession(sessions, actor);

        ToolRegistry registry = new ToolRegistry();
        CommonRuntime runtime = new CommonRuntime(
            registry,
            directScheduler(),
            ignored -> true,
            CLOCK
        );
        EmbeddedBrain brain = new EmbeddedBrain(
            SERVER_ID,
            capabilities(),
            sessions,
            new InMemoryConversationHistoryStore(),
            new AiRequestScheduler(Runnable::run),
            classifier(JevCategory.GENERAL),
            new DeterministicRoutePolicy(),
            new SequenceLuna(
                new LunaStep.Final(
                    "unused",
                    LunaStep.SessionState.CONTINUE
                )
            ),
            AuditSink.noOp(),
            runtime.openRuntime(
                UUID.fromString("13100000-0000-4000-8000-000000000001"),
                SERVER_ID,
                registry.tools()
            ),
            CLOCK
        );

        EmbeddedBrainGateway gateway = new EmbeddedBrainGateway(
            brain,
            sessions,
            new FakePlatform(true),
            directScheduler(),
            CLOCK
        );
        gateway.start();
        gateway.cancelSession(
            actor,
            sessionId,
            io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason.SESSION_ENDED
        );

        require(
            !sessions.isActive(actor, sessionId),
            "Gateway cancelSession did not update ChatSessionManager authority."
        );
        gateway.stop();
    }

    private static void gatewayStopSuppressesLateDelivery() {
        UUID actor = UUID.fromString("23200000-0000-4000-8000-000000000001");
        ChatSessionManager sessions = new ChatSessionManager(CLOCK);
        UUID sessionId = startSession(sessions, actor);

        ToolRegistry registry = new ToolRegistry();
        CommonRuntime runtime = new CommonRuntime(
            registry,
            directScheduler(),
            ignored -> true,
            CLOCK
        );

        CompletableFuture<LunaStep> gate = new CompletableFuture<>();
        EmbeddedBrain brain = new EmbeddedBrain(
            SERVER_ID,
            capabilities(),
            sessions,
            new InMemoryConversationHistoryStore(),
            new AiRequestScheduler(Runnable::run),
            classifier(JevCategory.GENERAL),
            new DeterministicRoutePolicy(),
            new GatedLuna(gate),
            AuditSink.noOp(),
            runtime.openRuntime(
                UUID.fromString("13200000-0000-4000-8000-000000000001"),
                SERVER_ID,
                registry.tools()
            ),
            CLOCK
        );

        FakePlatform platform = new FakePlatform(true);
        EmbeddedBrainGateway gateway = new EmbeddedBrainGateway(
            brain,
            sessions,
            platform,
            directScheduler(),
            CLOCK
        );
        gateway.start();

        boolean accepted = gateway.submitChat(
            actor,
            "Operator",
            sessionId,
            "DIRECT",
            "자비스 늦은 응답 테스트"
        ).toCompletableFuture().join();
        require(accepted, "Gateway did not accept stop-race fixture.");

        gateway.stop();
        gate.complete(
            new LunaStep.Final(
                "stop 이후 전달되면 안 됨",
                LunaStep.SessionState.CONTINUE
            )
        );

        require(
            platform.messages.isEmpty(),
            "Gateway delivered a response after stop."
        );
    }

    private static void jsonlAuditContract() throws Exception {
        Path directory = Files.createTempDirectory("jarvis-audit-e9-");
        AsyncJsonlAuditSink sink = new AsyncJsonlAuditSink(directory, CLOCK);

        AuditSink.AuditEvent event = new AuditSink.AuditEvent(
            NOW,
            SERVER_ID,
            UUID.fromString("24000000-0000-4000-8000-000000000001"),
            UUID.fromString("34000000-0000-4000-8000-000000000001"),
            UUID.fromString("54000000-0000-4000-8000-000000000001"),
            null,
            ToolName.GET_SERVER_STATUS,
            ToolName.GET_SERVER_STATUS.risk(),
            Map.of(
                "api_key", "sk-test-secret-value",
                "authorization", "Bearer should-not-leak"
            ),
            "OK",
            "Fake",
            12L,
            "gpt-6-luna",
            null
        );

        boolean recorded = sink.record(event).toCompletableFuture().join();
        require(recorded, "JSONL audit write did not confirm persistence.");

        Path file;
        try (var files = Files.list(directory)) {
            file = files.findFirst().orElseThrow();
        }
        String line = Files.readString(file, StandardCharsets.UTF_8);
        require(line.contains("[REDACTED]"), "Audit secrets were not masked.");
        require(!line.contains("sk-test-secret-value"), "OpenAI-like key leaked into audit.");
        require(!line.contains("should-not-leak"), "Authorization value leaked into audit.");
        require(
            sink.health().lastSuccessfulWriteAt() != null,
            "Audit health did not record successful write."
        );
        sink.closeAsync().toCompletableFuture().join();

        Path notDirectory = Files.createTempFile("jarvis-audit-file-", ".tmp");
        AsyncJsonlAuditSink broken = new AsyncJsonlAuditSink(notDirectory, CLOCK);
        boolean failed = broken.record(event).toCompletableFuture().join();
        require(!failed, "Audit sink reported success when directory creation failed.");
        require(
            broken.health().status() == AsyncJsonlAuditSink.Status.UNHEALTHY,
            "Audit IO failure did not mark sink unhealthy."
        );
        broken.closeAsync().toCompletableFuture().join();
    }

    private static void settingsContract() throws Exception {
        Path dataDirectory = Files.createTempDirectory(
            "jarvis-e15-settings-"
        );

        EmbeddedBrainSettings first = EmbeddedBrainSettings.resolve(
            "",
            "openai",
            "typesafe",
            dataDirectory
        );
        EmbeddedBrainSettings second = EmbeddedBrainSettings.resolve(
            null,
            "openai",
            "typesafe",
            dataDirectory
        );

        require(
            first.serverId().startsWith("local-"),
            "Auto-generated server id did not use the local prefix."
        );
        require(
            first.serverId().equals(second.serverId()),
            "Auto-generated server id was not stable across reloads."
        );
        require(
            Files.readString(dataDirectory.resolve("server-id.txt")).trim()
                .equals(first.serverId()),
            "Auto-generated server id was not persisted."
        );
        require(
            first.auditDirectory().equals(dataDirectory.resolve("audit")),
            "Default audit directory is not platform-data/audit."
        );

        EmbeddedBrainSettings overridden = EmbeddedBrainSettings.resolve(
            "explicit-server",
            "openai",
            "typesafe",
            Files.createTempDirectory("jarvis-e15-override-")
        );
        require(
            "explicit-server".equals(overridden.serverId()),
            "Explicit server id override was not honored."
        );

        try {
            EmbeddedBrainSettings.resolve(
                "",
                "",
                "typesafe",
                dataDirectory
            );
            throw new AssertionError("Blank OpenAI key was accepted.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }

        try {
            EmbeddedBrainSettings.resolve(
                "invalid server id",
                "openai",
                "typesafe",
                dataDirectory
            );
            throw new AssertionError("Invalid server id override was accepted.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static EmbeddedBrain.ChatRequest request(
        UUID requestId,
        UUID actor,
        UUID sessionId,
        String text
    ) {
        return new EmbeddedBrain.ChatRequest(
            requestId,
            actor,
            "Operator",
            sessionId,
            "DIRECT",
            text,
            NOW,
            NOW.plusMillis(RequestBudget.MAX_REQUEST_MILLIS)
        );
    }

    private static UUID startSession(
        ChatSessionManager sessions,
        UUID actor
    ) {
        ChatSessionManager.Decision decision =
            sessions.accept(actor, true, "자비스 테스트");
        require(
            decision.kind() == ChatSessionManager.Kind.FORWARD,
            "Fixture session did not start."
        );
        return decision.sessionId();
    }

    private static JevClassifier classifier(JevCategory category) {
        return (JevInput input, Instant deadlineAt) ->
            CompletableFuture.completedFuture(
                new JevClassification(
                    category,
                    0.99,
                    Map.of(category, 0.99),
                    "jev-1.13.0",
                    "typesafe_fixture"
                )
            );
    }

    private static List<Capability> capabilities() {
        return List.of(
            new Capability(
                "server.status",
                "Fixture",
                "1"
            ),
            new Capability(
                "staff.self_teleport",
                "Fixture",
                "1"
            )
        );
    }

    private static ServerScheduler directScheduler() {
        return new ServerScheduler() {
            @Override
            public <T> CompletionStage<T> submit(
                java.util.function.Supplier<CompletionStage<T>> task
            ) {
                try {
                    return task.get();
                } catch (RuntimeException failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
        };
    }

    private static void expectProtocolStage(
        ErrorCode code,
        CompletionStage<?> stage
    ) {
        try {
            stage.toCompletableFuture().join();
            throw new AssertionError(
                "Expected ProtocolException with code " + code + "."
            );
        } catch (CompletionException expected) {
            Throwable cause = expected.getCause();
            require(
                cause instanceof ProtocolException,
                "Expected ProtocolException, got " + cause
            );
            require(
                ((ProtocolException) cause).code() == code,
                "Unexpected ProtocolException code."
            );
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class SequenceLuna implements LunaClient {
        private final ArrayDeque<LunaStep> steps = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private boolean secondRoundSawToolResult;

        private SequenceLuna(LunaStep... steps) {
            this.steps.addAll(List.of(steps));
        }

        @Override
        public String modelId() {
            return "gpt-6-luna";
        }

        @Override
        public CompletionStage<LunaStep> next(
            LunaTurnInput input,
            DeterministicRoutePolicy.RoutingDecision routing
        ) {
            int call = calls.incrementAndGet();
            if (call == 2) {
                secondRoundSawToolResult = input.history().stream()
                    .anyMatch(ConversationEntry.ToolMessage.class::isInstance);
            }
            LunaStep step = steps.pollFirst();
            if (step == null) {
                return CompletableFuture.failedFuture(
                    new AssertionError("No Luna fixture step remains.")
                );
            }
            return CompletableFuture.completedFuture(step);
        }

        @Override
        public void clear(UUID requestId) {
        }
    }

    private static final class GatedLuna implements LunaClient {
        private final CompletableFuture<LunaStep> gate;

        private GatedLuna(CompletableFuture<LunaStep> gate) {
            this.gate = gate;
        }

        @Override
        public String modelId() {
            return "gpt-6-luna";
        }

        @Override
        public CompletionStage<LunaStep> next(
            LunaTurnInput input,
            DeterministicRoutePolicy.RoutingDecision routing
        ) {
            return gate;
        }

        @Override
        public void clear(UUID requestId) {
        }
    }

    private static final class RecordingAudit implements AuditSink {
        private final boolean recorded;
        private final List<AuditEvent> events = new ArrayList<>();

        private RecordingAudit(boolean recorded) {
            this.recorded = recorded;
        }

        @Override
        public CompletionStage<Boolean> record(AuditEvent event) {
            events.add(event);
            return CompletableFuture.completedFuture(recorded);
        }
    }

    private static final class FakePlatform implements AdapterPlatformAccess {
        private boolean operator;
        private final List<String> messages = new ArrayList<>();

        private FakePlatform(boolean operator) {
            this.operator = operator;
        }

        @Override
        public boolean isServerThread() {
            return true;
        }

        @Override
        public boolean isOnlineOperator(UUID playerUuid) {
            return operator;
        }

        @Override
        public void sendPrivatePlain(UUID requesterUuid, String text) {
            messages.add(text);
        }
    }
}
