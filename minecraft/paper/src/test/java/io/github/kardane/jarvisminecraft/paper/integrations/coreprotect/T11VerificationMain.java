package io.github.kardane.jarvisminecraft.paper.integrations.coreprotect;

import io.github.kardane.jarvisminecraft.common.protocol.Protocol;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.AreaHistoryArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.BlockChange;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.HistoryData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.Location;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerHistoryArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry.ToolExecutionContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class T11VerificationMain {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000001101");
    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-8000-000000001102");
    private static final UUID OTHER_ACTOR = UUID.fromString("00000000-0000-4000-8000-000000001103");
    private static final Location CENTER = new Location("world", 10, 64, 10, 0, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC);

    private T11VerificationMain() {
    }

    public static void main(String[] args) throws Exception {
        compatibilityContract();
        absentAndIncompatibleProviderContract();
        pagingAndSessionBindingContract();
        emptyAndPartialResultContract();
        timeoutContract();
        playerUuidContract();
        toolRegistrationContract();
        System.out.println("T11 verification OK");
    }

    private static void compatibilityContract() {
        require(!CoreProtectApiCompatibility.isSupported(false, false, false, false, 0), "missing plugin was accepted");
        require(!CoreProtectApiCompatibility.isSupported(true, false, true, true, 12), "wrong plugin type was accepted");
        require(!CoreProtectApiCompatibility.isSupported(true, true, false, true, 12), "disabled plugin was accepted");
        require(!CoreProtectApiCompatibility.isSupported(true, true, true, false, 12), "disabled API was accepted");
        require(!CoreProtectApiCompatibility.isSupported(true, true, true, true, 11), "old API was accepted");
        require(CoreProtectApiCompatibility.isSupported(true, true, true, true, 12), "minimum supported API was rejected");
        require(CoreProtectApiCompatibility.isSupported(true, true, true, true, 13), "newer compatible API was rejected");
    }

    private static void absentAndIncompatibleProviderContract() throws Exception {
        FakeBackend absent = new FakeBackend();
        absent.enabled = false;
        try (ProviderFixture fixture = provider(absent, Duration.ofSeconds(1))) {
            ToolResult result = await(fixture.provider.lookupPlayer(context(), new PlayerHistoryArguments(ACTOR, 300, null, 10)));
            require(result.error() != null && result.error().code() == Protocol.ErrorCode.PROVIDER_UNAVAILABLE,
                "absent Provider must fail explicitly");
        }

        FakeBackend incompatible = new FakeBackend();
        incompatible.version = 11;
        try (ProviderFixture fixture = provider(incompatible, Duration.ofSeconds(1))) {
            ToolResult result = await(fixture.provider.lookupPlayer(context(), new PlayerHistoryArguments(ACTOR, 300, null, 10)));
            require(result.error() != null && result.error().code() == Protocol.ErrorCode.PROVIDER_UNAVAILABLE,
                "API v11 must fail explicitly");
        }
    }

    private static void pagingAndSessionBindingContract() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.areaRecords = records(205, null);
        try (ProviderFixture fixture = provider(backend, Duration.ofSeconds(1))) {
            ToolExecutionContext context = context();
            ToolResult first = await(fixture.provider.lookupArea(context, new AreaHistoryArguments(CENTER, 8, 1800, null, 100)));
            HistoryData firstData = (HistoryData) first.data();
            require(first.status() == Protocol.ResultStatus.OK, "first history page must be OK");
            require(firstData.returnedCount() == 100 && firstData.totalCount() == 205, "first history page counts mismatch");
            require(firstData.nextCursor() != null && first.truncated(), "first page must have a cursor and mark partial response");

            ToolResult second = await(fixture.provider.lookupArea(
                context,
                new AreaHistoryArguments(CENTER, 8, 1800, firstData.nextCursor(), 100)
            ));
            HistoryData secondData = (HistoryData) second.data();
            require(secondData.returnedCount() == 100 && secondData.nextCursor() != null, "second history page mismatch");
            require(firstData.windowStart().equals(secondData.windowStart())
                && firstData.windowEnd().equals(secondData.windowEnd()), "page cursor must keep the original query window");

            ToolResult last = await(fixture.provider.lookupArea(
                context,
                new AreaHistoryArguments(CENTER, 8, 1800, secondData.nextCursor(), 100)
            ));
            HistoryData lastData = (HistoryData) last.data();
            require(lastData.returnedCount() == 5 && lastData.nextCursor() == null, "last history page mismatch");

            ToolResult wrongActor = await(fixture.provider.lookupArea(
                context(OTHER_ACTOR, SESSION),
                new AreaHistoryArguments(CENTER, 8, 1800, firstData.nextCursor(), 100)
            ));
            require(wrongActor.error() != null && wrongActor.error().code() == Protocol.ErrorCode.INVALID_ARGUMENT,
                "cursor must not cross actor sessions");
            ToolResult wrongQuery = await(fixture.provider.lookupArea(
                context,
                new AreaHistoryArguments(CENTER, 9, 1800, firstData.nextCursor(), 100)
            ));
            require(wrongQuery.error() != null && wrongQuery.error().code() == Protocol.ErrorCode.INVALID_ARGUMENT,
                "cursor must not cross queries");
        }
    }

    private static void emptyAndPartialResultContract() throws Exception {
        FakeBackend backend = new FakeBackend();
        try (ProviderFixture fixture = provider(backend, Duration.ofSeconds(1))) {
            ToolResult empty = await(fixture.provider.lookupArea(context(), new AreaHistoryArguments(CENTER, 10, 1800, null, 10)));
            require(empty.status() == Protocol.ResultStatus.EMPTY, "empty lookup must have EMPTY status");
            require(((HistoryData) empty.data()).totalCount() == 0, "complete empty lookup must report zero total results");
        }

        FakeBackend partialBackend = new FakeBackend();
        partialBackend.batch = new CoreProtectHistoryProvider.LookupBatch(List.of(), null, true, "CoreProtect partial test");
        try (ProviderFixture fixture = provider(partialBackend, Duration.ofSeconds(1))) {
            ToolResult partial = await(fixture.provider.lookupArea(context(), new AreaHistoryArguments(CENTER, 10, 1800, null, 10)));
            require(partial.status() == Protocol.ResultStatus.EMPTY, "partial zero-row lookup is still empty");
            require(partial.truncated() && ((HistoryData) partial.data()).totalCount() == null,
                "partial result must not claim a complete zero count");
        }
    }

    private static void timeoutContract() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.block = new CountDownLatch(1);
        try (ProviderFixture fixture = provider(backend, Duration.ofMillis(60))) {
            ToolResult result = await(fixture.provider.lookupArea(context(), new AreaHistoryArguments(CENTER, 10, 1800, null, 10)));
            require(result.error() != null && result.error().code() == Protocol.ErrorCode.TIMEOUT,
                "slow CoreProtect call must report TIMEOUT");
        }
    }

    private static void playerUuidContract() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.playerRecords = records(2, ACTOR);
        try (ProviderFixture fixture = provider(backend, Duration.ofSeconds(1))) {
            ToolResult result = await(fixture.provider.lookupPlayer(context(), new PlayerHistoryArguments(ACTOR, 3600, null, 20)));
            HistoryData data = (HistoryData) result.data();
            require(data.records().size() == 2, "player history records missing");
            require(data.records().stream().allMatch(record -> ACTOR.equals(record.actorUuid())),
                "verified CoreProtect player history must retain the resolved UUID");
        }
    }

    private static void toolRegistrationContract() {
        FakeBackend backend = new FakeBackend();
        try (ProviderFixture fixture = provider(backend, Duration.ofSeconds(1))) {
            ToolRegistry registry = new ToolRegistry();
            fixture.provider.registerTools(registry);
            require(registry.contains(Protocol.ToolName.LOOKUP_AREA_HISTORY), "area history Tool not registered by Provider");
            require(registry.contains(Protocol.ToolName.LOOKUP_PLAYER_HISTORY), "player history Tool not registered by Provider");
        }
    }

    private static List<BlockChange> records(int count, UUID actorUuid) {
        List<BlockChange> records = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            records.add(new BlockChange(
                Instant.parse("2026-09-24T00:00:00Z").minusSeconds(index),
                actorUuid,
                "Builder" + index,
                index % 2 == 0 ? "BREAK" : "PLACE",
                new Location("world", index, 64, 10, 0, 0),
                "minecraft:stone"
            ));
        }
        return List.copyOf(records);
    }

    private static ProviderFixture provider(FakeBackend backend, Duration timeout) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread thread = new Thread(runnable, "t11-test-worker");
                thread.setDaemon(true);
                return thread;
            }
        );
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "t11-test-timeout");
            thread.setDaemon(true);
            return thread;
        });
        CoreProtectHistoryProvider provider = new CoreProtectHistoryProvider(backend, CLOCK, executor, timer, timeout);
        return new ProviderFixture(provider, executor, timer);
    }

    private static ToolExecutionContext context() {
        return context(ACTOR, SESSION);
    }

    private static ToolExecutionContext context(UUID requester, UUID session) {
        return new ToolExecutionContext("main", UUID.randomUUID(), requester, UUID.randomUUID(), session,
            UUID.randomUUID(), null, CLOCK.instant().plusSeconds(20));
    }

    private static ToolResult await(java.util.concurrent.CompletionStage<ToolResult> stage) throws Exception {
        return stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeBackend implements CoreProtectHistoryProvider.Backend {
        private boolean enabled = true;
        private int version = 12;
        private List<BlockChange> areaRecords = List.of();
        private List<BlockChange> playerRecords = List.of();
        private CoreProtectHistoryProvider.LookupBatch batch;
        private CountDownLatch block;

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public int apiVersion() {
            return version;
        }

        @Override
        public Optional<Object> captureCenter(Location location) {
            return "missing".equals(location.worldId()) ? Optional.empty() : Optional.of(location);
        }

        @Override
        public CoreProtectHistoryProvider.LookupBatch lookupArea(Object capturedCenter, Location center, int radius, int lookbackSeconds) {
            waitIfRequested();
            return batch == null ? CoreProtectHistoryProvider.LookupBatch.complete(areaRecords, "CoreProtect fake") : batch;
        }

        @Override
        public CoreProtectHistoryProvider.LookupBatch lookupPlayer(UUID playerUuid, int lookbackSeconds) {
            waitIfRequested();
            return CoreProtectHistoryProvider.LookupBatch.complete(playerRecords, "CoreProtect fake player history");
        }

        private void waitIfRequested() {
            if (block == null) {
                return;
            }
            try {
                block.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record ProviderFixture(
        CoreProtectHistoryProvider provider,
        ThreadPoolExecutor executor,
        ScheduledExecutorService timer
    ) implements AutoCloseable {
        @Override
        public void close() {
            provider.close();
            executor.shutdownNow();
            timer.shutdownNow();
        }
    }
}
