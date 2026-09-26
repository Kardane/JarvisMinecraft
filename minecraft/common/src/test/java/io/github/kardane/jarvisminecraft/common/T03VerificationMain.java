package io.github.kardane.jarvisminecraft.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.ConversationEntry;
import io.github.kardane.jarvisminecraft.common.brain.InMemoryConversationHistoryStore;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolCodec;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolMessage;
import io.github.kardane.jarvisminecraft.common.protocol.ToolArgumentCodec;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
