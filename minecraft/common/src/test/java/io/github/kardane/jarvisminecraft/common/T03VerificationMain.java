package io.github.kardane.jarvisminecraft.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolCodec;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolMessage;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.TeleportArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.TeleportData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
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

        require(validCount == 31, "Expected 31 valid protocol fixtures.");
        require(invalidCount == 9, "Expected 9 invalid protocol fixtures.");
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
