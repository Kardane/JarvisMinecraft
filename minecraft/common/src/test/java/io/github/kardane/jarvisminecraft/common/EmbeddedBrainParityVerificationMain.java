package io.github.kardane.jarvisminecraft.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kardane.jarvisminecraft.common.brain.AiRequestScheduler;
import io.github.kardane.jarvisminecraft.common.brain.RequestBudget;
import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevCategory;
import io.github.kardane.jarvisminecraft.common.brain.ai.JevClassification;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ToolArgumentCodec;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.NoArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.TeleportArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class EmbeddedBrainParityVerificationMain {
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private EmbeddedBrainParityVerificationMain() {
    }

    public static void main(String[] args) throws Exception {
        JsonObject fixture = loadFixture();
        sharedRouteFixtures(fixture.getAsJsonArray("routeCases"));
        sharedArgumentFixtures(fixture.getAsJsonArray("argumentCases"));
        sharedLimitFixtures(fixture.getAsJsonObject("limits"));
        sessionIsolationAndCancellation();
        nonOperatorAndInactiveToolAreDenied();
        schedulerLimitsAndCancellation();
        stateChangingTimeoutIsOutcomeUnknownAndNotRetried();
        System.out.println("Embedded Brain E12 parity verification OK");
    }

    private static JsonObject loadFixture() throws Exception {
        Path repoRoot = Path.of(System.getProperty("jarvis.repoRoot", "."));
        Path path = repoRoot.resolve("evals/embedded-policy-parity.json");
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static void sharedRouteFixtures(JsonArray cases) {
        for (JsonElement element : cases) {
            JsonObject item = element.getAsJsonObject();
            String name = item.get("name").getAsString();
            Set<ToolName> active = EnumSet.noneOf(ToolName.class);
            for (JsonElement tool : item.getAsJsonArray("activeTools")) {
                active.add(ToolName.fromWire(tool.getAsString()));
            }

            DeterministicRoutePolicy policy = item.get("abstainBelow").isJsonNull()
                ? new DeterministicRoutePolicy()
                : new DeterministicRoutePolicy(item.get("abstainBelow").getAsDouble());

            DeterministicRoutePolicy.RoutingDecision decision;
            if ("ERROR".equals(item.get("mode").getAsString())) {
                decision = policy.errorFallback(active);
            } else {
                JevCategory category = JevCategory.valueOf(
                    item.get("category").getAsString()
                );
                double confidence = item.get("confidence").getAsDouble();
                decision = policy.route(
                    new JevClassification(
                        category,
                        confidence,
                        Map.of(category, confidence),
                        "jev-1.13.0",
                        "e12-fixture"
                    ),
                    active
                );
            }

            List<String> actualTools = decision.availableTools().stream()
                .map(ToolName::wireName)
                .sorted()
                .toList();
            List<String> expectedTools = new ArrayList<>();
            for (JsonElement tool : item.getAsJsonArray("expectedTools")) {
                expectedTools.add(tool.getAsString());
            }
            expectedTools.sort(Comparator.naturalOrder());

            String actualFallback = decision.fallbackReason() == null
                ? null
                : decision.fallbackReason().name();
            String expectedFallback = item.get("expectedFallback").isJsonNull()
                ? null
                : item.get("expectedFallback").getAsString();

            require(
                actualTools.equals(expectedTools),
                name + ": routed Tool subset differs: " + actualTools
            );
            require(
                java.util.Objects.equals(actualFallback, expectedFallback),
                name + ": fallback differs: " + actualFallback
            );
        }
    }

    private static void sharedArgumentFixtures(JsonArray cases) {
        ToolArgumentCodec codec = new ToolArgumentCodec();
        for (JsonElement element : cases) {
            JsonObject item = element.getAsJsonObject();
            String name = item.get("name").getAsString();
            boolean valid = item.get("valid").getAsBoolean();
            boolean accepted = true;
            try {
                ToolName tool = ToolName.fromWire(item.get("tool").getAsString());
                codec.parse(tool, item.getAsJsonObject("arguments"));
            } catch (ProtocolException | IllegalArgumentException failure) {
                accepted = false;
            }
            require(
                accepted == valid,
                name + ": argument validation parity differs"
            );
        }
    }

    private static void sharedLimitFixtures(JsonObject limits) {
        require(
            RequestBudget.MAX_TOOL_CALLS == limits.get("maxToolCalls").getAsInt(),
            "MAX_TOOL_CALLS differs from E12 fixture."
        );
        require(
            RequestBudget.MAX_MODEL_ROUNDS == limits.get("maxModelRounds").getAsInt(),
            "MAX_MODEL_ROUNDS differs from E12 fixture."
        );
        require(
            AiRequestScheduler.DEFAULT_MAX_CONCURRENT
                == limits.get("maxConcurrent").getAsInt(),
            "maxConcurrent differs from E12 fixture."
        );
        require(
            AiRequestScheduler.DEFAULT_MAX_QUEUED_PER_SESSION
                == limits.get("maxQueuedPerSession").getAsInt(),
            "maxQueuedPerSession differs from E12 fixture."
        );
        require(
            AiRequestScheduler.DEFAULT_MAX_QUEUED_TOTAL
                == limits.get("maxQueuedTotal").getAsInt(),
            "maxQueuedTotal differs from E12 fixture."
        );

        RequestBudget budget = new RequestBudget(
            NOW.plusSeconds(60),
            NOW
        );
        for (int i = 0; i < RequestBudget.MAX_MODEL_ROUNDS; i += 1) {
            budget.consumeModelRound(NOW);
        }
        expectCode(
            ErrorCode.BUSY,
            () -> budget.consumeModelRound(NOW),
            "model round budget"
        );

        RequestBudget toolBudget = new RequestBudget(
            NOW.plusSeconds(60),
            NOW
        );
        toolBudget.consumeToolCalls(RequestBudget.MAX_TOOL_CALLS, NOW);
        expectCode(
            ErrorCode.BUSY,
            () -> toolBudget.consumeToolCalls(1, NOW),
            "Tool call budget"
        );

        RequestBudget expired = new RequestBudget(NOW.plusSeconds(1), NOW);
        expectCode(
            ErrorCode.TIMEOUT,
            () -> expired.assertLive(NOW.plusSeconds(1)),
            "request deadline"
        );
    }

    private static void sessionIsolationAndCancellation() {
        ChatSessionManager sessions = new ChatSessionManager(FIXED_CLOCK);
        UUID first = UUID.fromString("61000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("62000000-0000-4000-8000-000000000001");

        ChatSessionManager.Decision firstStart =
            sessions.accept(first, true, "자비스 첫 세션");
        ChatSessionManager.Decision secondStart =
            sessions.accept(second, true, "자비스 둘째 세션");

        require(
            firstStart.kind() == ChatSessionManager.Kind.FORWARD
                && secondStart.kind() == ChatSessionManager.Kind.FORWARD,
            "Sessions did not start."
        );
        require(
            !firstStart.sessionId().equals(secondStart.sessionId()),
            "Different actors shared a session id."
        );
        sessions.end(first, firstStart.sessionId());
        require(
            !sessions.isActive(first, firstStart.sessionId()),
            "Ended session remained active."
        );
        require(
            sessions.isActive(second, secondStart.sessionId()),
            "Ending one actor session affected another actor."
        );

        sessions.invalidate(second);
        require(
            !sessions.isActive(second, secondStart.sessionId()),
            "Actor invalidation did not cancel the active session."
        );
    }

    private static void nonOperatorAndInactiveToolAreDenied() {
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

        CommonRuntime deniedRuntime = new CommonRuntime(
            registry,
            directScheduler(),
            ignored -> false,
            FIXED_CLOCK
        );
        CommonRuntime.ExecutionRuntime denied = deniedRuntime.openRuntime(
            UUID.fromString("63000000-0000-4000-8000-000000000001"),
            "main",
            EnumSet.of(ToolName.GET_SERVER_STATUS)
        );
        ToolResult unauthorized = denied.execute(
            readOnlyInvocation(ToolName.GET_SERVER_STATUS)
        ).toCompletableFuture().join();
        require(
            unauthorized.error().code() == ErrorCode.UNAUTHORIZED,
            "non-OP Tool execution was not denied."
        );
        require(calls.get() == 0, "non-OP request reached Tool handler.");

        CommonRuntime inactiveRuntime = new CommonRuntime(
            registry,
            directScheduler(),
            ignored -> true,
            FIXED_CLOCK
        );
        CommonRuntime.ExecutionRuntime inactive = inactiveRuntime.openRuntime(
            UUID.fromString("64000000-0000-4000-8000-000000000001"),
            "main",
            Set.of()
        );
        ToolResult unsupported = inactive.execute(
            readOnlyInvocation(ToolName.GET_SERVER_STATUS)
        ).toCompletableFuture().join();
        require(
            unsupported.error().code() == ErrorCode.UNSUPPORTED,
            "Inactive Tool was not rejected."
        );
        require(calls.get() == 0, "Inactive Tool reached Tool handler.");
    }

    private static void schedulerLimitsAndCancellation() {
        AiRequestScheduler scheduler = new AiRequestScheduler(Runnable::run);
        UUID actor = UUID.fromString("65000000-0000-4000-8000-000000000001");
        List<CompletableFuture<Integer>> blockers = new ArrayList<>();

        for (int i = 0; i < AiRequestScheduler.DEFAULT_MAX_CONCURRENT; i += 1) {
            CompletableFuture<Integer> blocker = new CompletableFuture<>();
            blockers.add(blocker);
            scheduler.submit(
                actor,
                uuid(700 + i),
                () -> blocker
            );
        }

        for (int i = 0; i < AiRequestScheduler.DEFAULT_MAX_QUEUED_TOTAL; i += 1) {
            int value = i;
            scheduler.submit(
                actor,
                uuid(800 + i),
                () -> CompletableFuture.completedFuture(value)
            );
        }
        require(
            scheduler.snapshot().queuedTotal()
                == AiRequestScheduler.DEFAULT_MAX_QUEUED_TOTAL,
            "Global queue did not reach configured bound."
        );
        expectStageCode(
            ErrorCode.BUSY,
            scheduler.submit(
                actor,
                uuid(999),
                () -> CompletableFuture.completedFuture(999)
            ),
            "global queue overflow"
        );

        AiRequestScheduler perSession = new AiRequestScheduler(
            Runnable::run,
            1,
            2,
            16
        );
        UUID session = uuid(1000);
        CompletableFuture<Integer> active = new CompletableFuture<>();
        perSession.submit(actor, session, () -> active);
        CompletionStage<Integer> queuedOne = perSession.submit(
            actor,
            session,
            () -> CompletableFuture.completedFuture(1)
        );
        CompletionStage<Integer> queuedTwo = perSession.submit(
            actor,
            session,
            () -> CompletableFuture.completedFuture(2)
        );
        expectStageCode(
            ErrorCode.BUSY,
            perSession.submit(
                actor,
                session,
                () -> CompletableFuture.completedFuture(3)
            ),
            "per-session queue overflow"
        );
        perSession.cancelSession(actor, session);
        expectStageCode(ErrorCode.CANCELLED, queuedOne, "queued cancellation 1");
        expectStageCode(ErrorCode.CANCELLED, queuedTwo, "queued cancellation 2");
        active.complete(0);

        blockers.forEach(blocker -> blocker.complete(0));
        scheduler.shutdown();
        perSession.shutdown();
    }

    private static void stateChangingTimeoutIsOutcomeUnknownAndNotRetried() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register(
            ToolName.TELEPORT_STAFF,
            TeleportArguments.class,
            (context, arguments) -> {
                calls.incrementAndGet();
                return new CompletableFuture<>();
            }
        );

        Clock clock = Clock.systemUTC();
        CommonRuntime runtime = new CommonRuntime(
            registry,
            directScheduler(),
            ignored -> true,
            clock
        );
        CommonRuntime.ExecutionRuntime execution = runtime.openRuntime(
            UUID.randomUUID(),
            "main",
            EnumSet.of(ToolName.TELEPORT_STAFF)
        );

        Instant sentAt = clock.instant();
        ToolResult result = execution.execute(
            new CommonRuntime.ToolInvocation(
                sentAt,
                sentAt.plus(Duration.ofMillis(80)),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ToolName.TELEPORT_STAFF,
                new TeleportArguments(UUID.randomUUID())
            )
        ).toCompletableFuture().join();

        require(
            result.error().code() == ErrorCode.OUTCOME_UNKNOWN,
            "State-changing timeout did not return OUTCOME_UNKNOWN."
        );
        require(!result.error().retryable(), "OUTCOME_UNKNOWN was marked retryable.");
        require(calls.get() == 1, "State-changing Tool was retried automatically.");
    }

    private static CommonRuntime.ToolInvocation readOnlyInvocation(ToolName tool) {
        return new CommonRuntime.ToolInvocation(
            NOW,
            NOW.plusSeconds(5),
            UUID.fromString("66000000-0000-4000-8000-000000000001"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            tool,
            new NoArguments()
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

    private static UUID uuid(int suffix) {
        return UUID.fromString(
            String.format("67000000-0000-4000-8000-%012d", suffix)
        );
    }

    private static void expectCode(
        ErrorCode code,
        Runnable action,
        String label
    ) {
        try {
            action.run();
            throw new AssertionError(label + ": expected " + code);
        } catch (ProtocolException failure) {
            require(
                failure.code() == code,
                label + ": expected " + code + " but got " + failure.code()
            );
        }
    }

    private static void expectStageCode(
        ErrorCode code,
        CompletionStage<?> stage,
        String label
    ) {
        try {
            stage.toCompletableFuture().join();
            throw new AssertionError(label + ": expected " + code);
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            require(
                cause instanceof ProtocolException
                    && ((ProtocolException) cause).code() == code,
                label + ": expected " + code + " but got " + cause
            );
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
