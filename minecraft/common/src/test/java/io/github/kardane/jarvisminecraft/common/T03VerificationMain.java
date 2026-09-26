package io.github.kardane.jarvisminecraft.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.ConversationEntry;
import io.github.kardane.jarvisminecraft.common.brain.InMemoryConversationHistoryStore;
import io.github.kardane.jarvisminecraft.common.brain.AiRequestScheduler;
import io.github.kardane.jarvisminecraft.common.brain.RequestBudget;
import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.JdkJevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevCategory;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassification;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaPrompt;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaToolSchemas;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaTurnInput;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolCodec;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolMessage;
import io.github.kardane.jarvisminecraft.common.protocol.ToolArgumentCodec;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.GetPlayerByNameArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.TeleportArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.TeleportData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.transport.AdapterBrainConnection;
import io.github.kardane.jarvisminecraft.common.transport.SharedSecretAuthenticator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;
import static io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry.ToolExecutionContext;

public final class T03VerificationMain {
    private static final ProtocolCodec CODEC = new ProtocolCodec();
    private static final Instant FIXTURE_NOW = Instant.parse("2026-09-23T15:10:01Z");

    private T03VerificationMain() {
    }

    public static void main(String[] args) throws Exception {
        Path repoRoot = Path.of(requireProperty("jarvis.repoRoot"));
        fixtureContract(repoRoot);
        platformIsolation(repoRoot);
        brainGatewayContract();
        toolArgumentContract();
        conversationHistoryContract();
        requestBudgetContract();
        aiRequestSchedulerContract();
        jevRoutingContract();
        lunaContract();
        sharedSecretContract();
        deadlineContract(repoRoot);
        schedulerDedupAndReconnectContract(repoRoot);
        System.out.println("T03 verification OK");
    }

    private static void fixtureContract(Path repoRoot) throws IOException {
        Path fixtureRoot = repoRoot.resolve("protocol/fixtures");
        JsonObject manifest = JsonParser.parseString(
            Files.readString(fixtureRoot.resolve("manifest.json"), StandardCharsets.UTF_8)
        ).getAsJsonObject();

        int validCount = 0;
        for (var element : manifest.getAsJsonArray("valid")) {
            Path file = fixtureRoot.resolve("valid").resolve(element.getAsString());
            ProtocolMessage message = CODEC.decode(Files.readString(file, StandardCharsets.UTF_8));
            CODEC.decode(CODEC.encode(message));
            validCount++;
        }

        int invalidCount = 0;
        JsonArray invalid = manifest.getAsJsonArray("invalid");
        for (var element : invalid) {
            String name = element.getAsJsonObject().get("file").getAsString();
            Path file = fixtureRoot.resolve("invalid").resolve(name);
            expectProtocolFailure(() -> CODEC.decode(Files.readString(file, StandardCharsets.UTF_8)));
            invalidCount++;
        }

        require(validCount == 33, "Expected 33 valid protocol fixtures.");
        require(invalidCount == 10, "Expected 10 invalid protocol fixtures.");
    }

    private static void platformIsolation(Path repoRoot) throws IOException {
        Path sourceRoot = repoRoot.resolve("minecraft/common/src/main/java");
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                require(!source.contains("org.bukkit"), "common imports Bukkit: " + path);
                require(!source.contains("io.papermc"), "common imports Paper: " + path);
                require(!source.contains("net.fabricmc"), "common imports Fabric: " + path);
                require(!source.contains("net.neoforged"), "common imports NeoForge: " + path);
            }
        }
    }

    private static void brainGatewayContract() {
        require(
            BrainGateway.class.isAssignableFrom(AdapterBrainConnection.class),
            "Remote AdapterBrainConnection must implement BrainGateway."
        );
    }

    private static void toolArgumentContract() {
        ToolArgumentCodec arguments = new ToolArgumentCodec();

        UUID target = UUID.fromString("22222222-2222-4222-8222-222222222222");
        JsonObject teleport = new JsonObject();
        teleport.addProperty("targetPlayerUuid", target.toString());

        var parsed = arguments.parse(ToolName.TELEPORT_STAFF, teleport);
        require(parsed instanceof TeleportArguments, "Teleport arguments were not typed.");
        require(
            ((TeleportArguments) parsed).targetPlayerUuid().equals(target),
            "Teleport target changed during parsing."
        );

        JsonObject unknownField = teleport.deepCopy();
        unknownField.addProperty("unexpected", true);
        expectProtocolFailure(() -> arguments.parse(ToolName.TELEPORT_STAFF, unknownField));

        JsonObject nearby = new JsonObject();
        JsonObject center = new JsonObject();
        center.addProperty("worldId", "minecraft:overworld");
        center.addProperty("x", 0);
        center.addProperty("y", 64);
        center.addProperty("z", 0);
        center.addProperty("yaw", 0);
        center.addProperty("pitch", 0);
        nearby.add("center", center);
        nearby.addProperty("radius", 65);
        nearby.addProperty("limit", 10);
        expectProtocolFailure(() -> arguments.parse(ToolName.GET_NEARBY_PLAYERS, nearby));

        require(
            ToolName.TELEPORT_STAFF.stateChanging(),
            "ToolName must remain the source of state-changing metadata."
        );
        require(
            "staff.self_teleport".equals(ToolName.TELEPORT_STAFF.capability()),
            "ToolName capability metadata changed unexpectedly."
        );
    }

    private static void conversationHistoryContract() {
        InMemoryConversationHistoryStore history =
            new InMemoryConversationHistoryStore(2);

        UUID actor = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID otherActor = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID session = UUID.fromString("44444444-4444-4444-8444-444444444444");
        UUID otherSession = UUID.fromString("55555555-5555-4555-8555-555555555555");

        history.append(
            actor,
            session,
            new ConversationEntry.UserMessage("one", UUID.randomUUID(), FIXTURE_NOW)
        );
        history.append(
            actor,
            session,
            new ConversationEntry.AssistantMessage("two", UUID.randomUUID(), FIXTURE_NOW.plusSeconds(1))
        );
        history.append(
            actor,
            session,
            new ConversationEntry.UserMessage("three", UUID.randomUUID(), FIXTURE_NOW.plusSeconds(2))
        );

        var bounded = history.history(actor, session);
        require(bounded.size() == 2, "Conversation history did not enforce its bound.");
        require(
            bounded.get(0) instanceof ConversationEntry.AssistantMessage,
            "Conversation history did not evict the oldest entry."
        );

        history.append(
            actor,
            otherSession,
            new ConversationEntry.UserMessage("other session", UUID.randomUUID(), FIXTURE_NOW)
        );
        history.append(
            otherActor,
            session,
            new ConversationEntry.UserMessage("other actor", UUID.randomUUID(), FIXTURE_NOW)
        );

        require(
            history.history(actor, otherSession).size() == 1,
            "Conversation histories leaked across sessions."
        );
        require(
            history.history(otherActor, session).size() == 1,
            "Conversation histories leaked across actors."
        );

        history.clearSession(actor, session);
        require(history.history(actor, session).isEmpty(), "Session history was not cleared.");
        require(
            history.history(actor, otherSession).size() == 1,
            "Clearing one session removed another session."
        );

        history.clearActor(actor);
        require(
            history.history(actor, otherSession).isEmpty(),
            "Actor history was not cleared."
        );
        require(
            history.history(otherActor, session).size() == 1,
            "Clearing one actor removed another actor."
        );
    }

    private static void requestBudgetContract() {
        Instant startedAt = FIXTURE_NOW;
        RequestBudget bounded = new RequestBudget(
            startedAt.plusSeconds(10),
            startedAt
        );
        require(
            bounded.deadlineAt().equals(startedAt.plusSeconds(10)),
            "RequestBudget must honor the earlier Adapter deadline."
        );

        for (int index = 0; index < RequestBudget.MAX_MODEL_ROUNDS; index += 1) {
            bounded.consumeModelRound(startedAt.plusSeconds(1));
        }
        require(
            bounded.remainingModelRounds() == 0,
            "RequestBudget model rounds were not consumed."
        );
        expectProtocolCode(
            ErrorCode.BUSY,
            () -> bounded.consumeModelRound(startedAt.plusSeconds(1))
        );

        RequestBudget tools = new RequestBudget(
            startedAt.plusSeconds(60),
            startedAt
        );
        tools.consumeToolCalls(RequestBudget.MAX_TOOL_CALLS, startedAt.plusSeconds(1));
        require(
            tools.remainingToolCalls() == 0,
            "RequestBudget Tool calls were not consumed."
        );
        expectProtocolCode(
            ErrorCode.BUSY,
            () -> tools.consumeToolCalls(1, startedAt.plusSeconds(1))
        );

        RequestBudget expired = new RequestBudget(
            startedAt.plusSeconds(5),
            startedAt
        );
        expectProtocolCode(
            ErrorCode.TIMEOUT,
            () -> expired.assertLive(startedAt.plusSeconds(5))
        );
    }

    private static void aiRequestSchedulerContract() {
        AiRequestScheduler scheduler =
            new AiRequestScheduler(Runnable::run, 2, 1, 2);

        UUID actorOne = UUID.fromString("61111111-1111-4111-8111-111111111111");
        UUID actorTwo = UUID.fromString("62222222-2222-4222-8222-222222222222");
        UUID actorThree = UUID.fromString("63333333-3333-4333-8333-333333333333");
        UUID actorFour = UUID.fromString("64444444-4444-4444-8444-444444444444");
        UUID sessionOne = UUID.fromString("71111111-1111-4111-8111-111111111111");
        UUID sessionTwo = UUID.fromString("72222222-2222-4222-8222-222222222222");
        UUID sessionThree = UUID.fromString("73333333-3333-4333-8333-333333333333");
        UUID sessionFour = UUID.fromString("74444444-4444-4444-8444-444444444444");

        AtomicInteger starts = new AtomicInteger();
        CompletableFuture<String> firstGate = new CompletableFuture<>();
        CompletableFuture<String> secondActorGate = new CompletableFuture<>();

        CompletionStage<String> first = scheduler.submit(
            actorOne,
            sessionOne,
            () -> {
                starts.incrementAndGet();
                return firstGate;
            }
        );
        CompletionStage<String> sameSessionQueued = scheduler.submit(
            actorOne,
            sessionOne,
            () -> {
                starts.incrementAndGet();
                return CompletableFuture.completedFuture("second");
            }
        );
        CompletionStage<String> secondActor = scheduler.submit(
            actorTwo,
            sessionTwo,
            () -> {
                starts.incrementAndGet();
                return secondActorGate;
            }
        );
        CompletionStage<String> thirdActorQueued = scheduler.submit(
            actorThree,
            sessionThree,
            () -> {
                starts.incrementAndGet();
                return CompletableFuture.completedFuture("third");
            }
        );

        require(starts.get() == 2, "AI scheduler exceeded concurrency or session serialization.");
        require(
            scheduler.snapshot().activeRequests() == 2
                && scheduler.snapshot().queuedTotal() == 2,
            "AI scheduler snapshot did not reflect bounded active/queued work."
        );

        CompletionStage<String> overflow = scheduler.submit(
            actorFour,
            sessionFour,
            () -> CompletableFuture.completedFuture("overflow")
        );
        expectStageProtocolCode(ErrorCode.BUSY, overflow);

        scheduler.cancelSession(actorThree, sessionThree);
        expectStageProtocolCode(ErrorCode.CANCELLED, thirdActorQueued);
        require(
            scheduler.snapshot().queuedTotal() == 1,
            "Session cancellation did not remove queued AI work."
        );

        firstGate.complete("first");
        require("first".equals(first.toCompletableFuture().join()), "First AI request failed.");
        require(
            "second".equals(sameSessionQueued.toCompletableFuture().join()),
            "Queued same-session request did not run after the active request."
        );
        require(starts.get() == 3, "Same-session request did not serialize.");

        secondActorGate.complete("other");
        require(
            "other".equals(secondActor.toCompletableFuture().join()),
            "Second actor AI request failed."
        );

        scheduler.shutdown();
        CompletionStage<String> afterShutdown = scheduler.submit(
            actorOne,
            sessionOne,
            () -> CompletableFuture.completedFuture("late")
        );
        expectStageProtocolCode(ErrorCode.CANCELLED, afterShutdown);
    }

    private static void jevRoutingContract() {
        require(
            "jev-1.13.0".equals(JdkJevClassifier.MODEL),
            "Jev model pin changed unexpectedly."
        );
        require(
            JdkJevClassifier.MAX_TIMEOUT.equals(java.time.Duration.ofSeconds(3)),
            "Jev timeout pin changed unexpectedly."
        );

        EnumSet<ToolName> active = EnumSet.of(
            ToolName.GET_SERVER_STATUS,
            ToolName.GET_PLAYER,
            ToolName.GET_PLAYER_LOCATION,
            ToolName.TELEPORT_STAFF,
            ToolName.GET_WORLD_INFO
        );

        DeterministicRoutePolicy policy = new DeterministicRoutePolicy(0.80);
        JevClassification action = new JevClassification(
            JevCategory.ACTION_REQUEST,
            0.95,
            Map.of(JevCategory.ACTION_REQUEST, 0.95),
            JdkJevClassifier.MODEL,
            "req_action"
        );
        var routed = policy.route(action, active);
        require(
            routed.availableTools().equals(
                EnumSet.of(
                    ToolName.GET_PLAYER,
                    ToolName.GET_PLAYER_LOCATION,
                    ToolName.TELEPORT_STAFF
                )
            ),
            "ACTION_REQUEST route exposed the wrong Tool subset."
        );
        require(!routed.fallbackActive(), "Healthy Jev route unexpectedly entered fallback.");

        JevClassification lowConfidence = new JevClassification(
            JevCategory.ACTION_REQUEST,
            0.40,
            Map.of(JevCategory.ACTION_REQUEST, 0.40),
            JdkJevClassifier.MODEL,
            "req_low"
        );
        var fallback = policy.route(lowConfidence, active);
        require(fallback.fallbackActive(), "Low-confidence Jev result did not enter fallback.");
        require(
            fallback.fallbackReason()
                == DeterministicRoutePolicy.FallbackReason.JEV_LOW_CONFIDENCE,
            "Low-confidence fallback reason changed."
        );
        require(
            !fallback.availableTools().contains(ToolName.TELEPORT_STAFF),
            "Read-only fallback exposed a state-changing Tool."
        );
        require(
            fallback.availableTools().contains(ToolName.GET_SERVER_STATUS),
            "Read-only fallback lost an active read-only Tool."
        );

        var errorFallback = policy.errorFallback(active);
        require(
            errorFallback.fallbackReason()
                == DeterministicRoutePolicy.FallbackReason.JEV_ERROR,
            "Jev error fallback reason changed."
        );
        require(
            !errorFallback.availableTools().contains(ToolName.TELEPORT_STAFF),
            "Jev error fallback exposed a state-changing Tool."
        );
    }

    private static void lunaContract() {
        LunaToolSchemas schemas = new LunaToolSchemas(new ToolArgumentCodec());
        EnumSet<ToolName> active =
            EnumSet.of(ToolName.GET_PLAYER, ToolName.TELEPORT_STAFF);

        var definitions = schemas.definitions(active);
        require(definitions.size() == 3, "Luna AI Tool aliases changed unexpectedly.");
        require(
            definitions.stream().allMatch(
                definition ->
                    definition.parameters()
                        .get("additionalProperties")
                        .getAsBoolean() == false
            ),
            "Luna function schemas must reject additional properties."
        );

        var playerCall = schemas.translate(
            "get_player_by_name",
            "{\"exactName\":\"Steve\"}",
            active
        );
        require(
            playerCall.tool() == ToolName.GET_PLAYER
                && playerCall.arguments() instanceof GetPlayerByNameArguments,
            "Luna player alias did not translate to the core Tool contract."
        );

        expectFailure(
            () -> schemas.translate(
                "teleport_staff",
                "{\"targetPlayerUuid\":\"22222222-2222-4222-8222-222222222222\"}",
                EnumSet.of(ToolName.GET_PLAYER)
            )
        );

        UUID requestId = UUID.fromString("81111111-1111-4111-8111-111111111111");
        LunaTurnInput turn = new LunaTurnInput(
            requestId,
            "Operator",
            List.of(
                new ConversationEntry.UserMessage(
                    "자비스 서버 상태 알려줘",
                    requestId,
                    FIXTURE_NOW
                )
            ),
            List.of(),
            EnumSet.of(ToolName.GET_SERVER_STATUS),
            RequestBudget.MAX_TOOL_CALLS,
            RequestBudget.MAX_MODEL_ROUNDS,
            FIXTURE_NOW.plusSeconds(30)
        );

        String rendered = LunaPrompt.renderConversation(turn);
        require(
            rendered.contains("USER: 자비스 서버 상태 알려줘"),
            "Luna conversation rendering lost the latest user message."
        );
        require(
            "gpt-6-luna".equals(LunaPrompt.MODEL),
            "Luna model pin changed unexpectedly."
        );

        DeterministicRoutePolicy policy = new DeterministicRoutePolicy();
        String degradedInstructions = LunaPrompt.instructions(
            policy.errorFallback(EnumSet.of(ToolName.GET_SERVER_STATUS))
        );
        require(
            degradedInstructions.contains("ROUTING FALLBACK IS ACTIVE."),
            "Luna fallback instructions were not applied."
        );
    }

    private static void sharedSecretContract() {
        expectFailure(() -> new SharedSecretAuthenticator(""));
        expectFailure(() -> new SharedSecretAuthenticator("CHANGE_ME"));
        SharedSecretAuthenticator auth = new SharedSecretAuthenticator("correct-horse-battery-staple");
        require(auth.authenticate("correct-horse-battery-staple"), "Correct secret was rejected.");
        require(!auth.authenticate("wrong-horse-battery-staple"), "Wrong secret was accepted.");
        require(!auth.authenticate(null), "Null secret was accepted.");
    }

    private static void deadlineContract(Path repoRoot) throws IOException {
        ProtocolMessage request = readFixture(repoRoot, "tool-request-location.json");
        FakeScheduler scheduler = new FakeScheduler();
        ToolRegistry registry = new ToolRegistry();
        registry.register(
            ToolName.GET_PLAYER_LOCATION,
            io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerUuidArguments.class,
            (ctx, arguments) -> CompletableFuture.completedFuture(
                ToolResult.error(ErrorCode.NOT_FOUND, "not used", false, FIXTURE_NOW, "Fake")
            )
        );

        CommonRuntime runtime = new CommonRuntime(
            registry,
            scheduler,
            ignored -> true,
            Clock.fixed(Instant.parse("2026-09-23T15:11:00Z"), ZoneOffset.UTC)
        );
        ToolResult result = runtime.openConnection(
            UUID.randomUUID(),
            "main",
            EnumSet.of(ToolName.GET_PLAYER_LOCATION)
        ).execute(request).toCompletableFuture().join();

        require(result.status() == ResultStatus.ERROR, "Expired request must fail.");
        require(result.error().code() == ErrorCode.TIMEOUT, "Expired request must return TIMEOUT.");
        require(scheduler.invocations.get() == 0, "Expired request reached scheduler.");
    }

    private static void schedulerDedupAndReconnectContract(Path repoRoot) throws IOException {
        ProtocolMessage original = readFixture(repoRoot, "tool-request-teleport.json");
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger handlerCalls = new AtomicInteger();

        ToolRegistry registry = new ToolRegistry();
        registry.register(
            ToolName.TELEPORT_STAFF,
            TeleportArguments.class,
            (ToolExecutionContext ctx, TeleportArguments arguments) -> {
                handlerCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    ToolResult.ok(
                        new TeleportData(
                            ctx.requesterUuid(),
                            arguments.targetPlayerUuid(),
                            "world",
                            "world",
                            true
                        ),
                        FIXTURE_NOW.plusSeconds(1),
                        "FakePlatform"
                    )
                );
            }
        );

        CommonRuntime runtime = new CommonRuntime(
            registry,
            scheduler,
            ignored -> true,
            Clock.fixed(FIXTURE_NOW, ZoneOffset.UTC)
        );

        UUID firstConnectionId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        var first = runtime.openConnection(
            firstConnectionId,
            "main",
            EnumSet.of(ToolName.TELEPORT_STAFF)
        );

        ToolResult firstResult = first.execute(original).toCompletableFuture().join();
        ToolResult duplicateToolCall = first.execute(original).toCompletableFuture().join();

        require(firstResult.status() == ResultStatus.OK, "First action did not succeed.");
        require(duplicateToolCall.status() == ResultStatus.OK, "Duplicate Tool call did not reuse result.");
        require(handlerCalls.get() == 1, "Duplicate Tool call re-executed handler.");
        require(scheduler.invocations.get() == 1, "Tool execution bypassed or repeated scheduler.");

        ProtocolMessage replayWithNewToolCall = new ProtocolMessage(
            original.protocolVersion(),
            original.type(),
            UUID.randomUUID(),
            original.requestId(),
            original.serverId(),
            original.sessionId(),
            original.requesterUuid(),
            original.sentAt(),
            original.deadlineAt(),
            original.payload(),
            UUID.randomUUID(),
            original.actionId()
        );

        var reconnected = runtime.openConnection(
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            "main",
            EnumSet.of(ToolName.TELEPORT_STAFF)
        );
        ToolResult replayResult = reconnected.execute(replayWithNewToolCall).toCompletableFuture().join();

        require(replayResult.status() == ResultStatus.ERROR, "Reconnect replay was not rejected.");
        require(replayResult.error().code() == ErrorCode.CANCELLED, "Reconnect replay must be CANCELLED.");
        require(handlerCalls.get() == 1, "Old action replay executed after reconnect.");
    }

    private static ProtocolMessage readFixture(Path repoRoot, String name) throws IOException {
        return CODEC.decode(
            Files.readString(
                repoRoot.resolve("protocol/fixtures/valid").resolve(name),
                StandardCharsets.UTF_8
            )
        );
    }

    private static String requireProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new AssertionError("Missing system property: " + name);
        }
        return value;
    }

    private static void expectProtocolFailure(ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("Expected ProtocolException.");
        } catch (ProtocolException expected) {
            // Expected.
        } catch (Exception other) {
            throw new AssertionError("Expected ProtocolException, got " + other, other);
        }
    }

    private static void expectProtocolCode(ErrorCode code, Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected ProtocolException with code " + code + ".");
        } catch (ProtocolException expected) {
            require(expected.code() == code, "Unexpected protocol error code: " + expected.code());
        }
    }

    private static void expectStageProtocolCode(
        ErrorCode code,
        CompletionStage<?> stage
    ) {
        try {
            stage.toCompletableFuture().join();
            throw new AssertionError("Expected failed stage with code " + code + ".");
        } catch (CompletionException expected) {
            Throwable cause = expected.getCause();
            require(
                cause instanceof ProtocolException,
                "Expected ProtocolException stage failure, got " + cause
            );
            require(
                ((ProtocolException) cause).code() == code,
                "Unexpected stage protocol error code: "
                    + ((ProtocolException) cause).code()
            );
        }
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected failure.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class FakeScheduler implements ServerScheduler {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public <T> CompletionStage<T> submit(java.util.function.Supplier<CompletionStage<T>> task) {
            invocations.incrementAndGet();
            try {
                return task.get();
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }
}
