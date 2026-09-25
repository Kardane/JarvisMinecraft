package io.github.kardane.jarvisminecraft.paper.integrations.coreprotect;

import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus;
import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Read-only CoreProtect history Tools. Database access is delegated to a bounded worker pool;
 * API-specific Bukkit objects are captured by Backend.captureCenter on the caller thread.
 */
public final class CoreProtectHistoryProvider implements AutoCloseable {
    public static final int MINIMUM_API_VERSION = 12;
    public static final String SOURCE = "CoreProtect API v12+";

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SNAPSHOT_RECORDS = 4_096;
    private static final int MAX_SNAPSHOTS = 64;
    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(2);
    private static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(6);

    private final Backend backend;
    private final Clock clock;
    private final ExecutorService lookupExecutor;
    private final ScheduledExecutorService timeoutScheduler;
    private final Duration queryTimeout;
    private final boolean ownsExecutors;
    private final LinkedHashMap<UUID, Snapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true);

    public CoreProtectHistoryProvider(
        Backend backend,
        Clock clock,
        ExecutorService lookupExecutor,
        ScheduledExecutorService timeoutScheduler,
        Duration queryTimeout
    ) {
        this(backend, clock, lookupExecutor, timeoutScheduler, queryTimeout, false);
    }

    private CoreProtectHistoryProvider(
        Backend backend,
        Clock clock,
        ExecutorService lookupExecutor,
        ScheduledExecutorService timeoutScheduler,
        Duration queryTimeout,
        boolean ownsExecutors
    ) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lookupExecutor = Objects.requireNonNull(lookupExecutor, "lookupExecutor");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.queryTimeout = Objects.requireNonNull(queryTimeout, "queryTimeout");
        if (queryTimeout.isZero() || queryTimeout.isNegative()) {
            throw new IllegalArgumentException("queryTimeout must be positive");
        }
        this.ownsExecutors = ownsExecutors;
    }

    /** Creates a provider with its own bounded worker and timeout threads. */
    public static CoreProtectHistoryProvider create(Backend backend, Clock clock) {
        ThreadFactory workerFactory = daemonFactory("jarvis-coreprotect-lookup");
        ExecutorService workers = new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32),
            workerFactory,
            new ThreadPoolExecutor.AbortPolicy()
        );
        ScheduledExecutorService timer = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(daemonFactory("jarvis-coreprotect-timeout"));
        return new CoreProtectHistoryProvider(
            backend,
            clock,
            workers,
            timer,
            DEFAULT_QUERY_TIMEOUT,
            true
        );
    }

    public void registerTools(ToolRegistry registry) {
        registry.register(ToolName.LOOKUP_AREA_HISTORY, AreaHistoryArguments.class, this::lookupArea);
        registry.register(ToolName.LOOKUP_PLAYER_HISTORY, PlayerHistoryArguments.class, this::lookupPlayer);
    }

    public CompletionStage<ToolResult> lookupArea(
        ToolExecutionContext context,
        AreaHistoryArguments arguments
    ) {
        if (!valid(context) || !valid(arguments)) {
            return completed(error(ErrorCode.INVALID_ARGUMENT, "Invalid history query.", false));
        }
        if (!available()) {
            return completed(error(ErrorCode.PROVIDER_UNAVAILABLE, "CoreProtect API is unavailable or incompatible.", true));
        }

        AreaKey key = new AreaKey(arguments.center(), arguments.radius(), arguments.lookbackSeconds());
        if (arguments.cursor() != null) {
            return completed(pageFromCursor(context, key, arguments.cursor(), arguments.limit()));
        }

        final Object capturedCenter;
        try {
            Optional<Object> captured = backend.captureCenter(arguments.center());
            if (captured.isEmpty()) {
                return completed(error(ErrorCode.NOT_FOUND, "The requested world is not loaded.", false));
            }
            capturedCenter = captured.get();
        } catch (RuntimeException failure) {
            return completed(error(ErrorCode.PROVIDER_UNAVAILABLE, "CoreProtect query setup failed.", true));
        }

        return submit(context, () -> {
            Instant windowEnd = clock.instant();
            LookupBatch batch = backend.lookupArea(
                capturedCenter,
                arguments.center(),
                arguments.radius(),
                arguments.lookbackSeconds()
            );
            return firstPage(
                context,
                key,
                arguments.limit(),
                batch,
                windowEnd.minusSeconds(arguments.lookbackSeconds()),
                windowEnd
            );
        });
    }

    public CompletionStage<ToolResult> lookupPlayer(
        ToolExecutionContext context,
        PlayerHistoryArguments arguments
    ) {
        if (!valid(context) || arguments == null || arguments.playerUuid() == null
            || !validLookback(arguments.lookbackSeconds()) || !validPageSize(arguments.limit())) {
            return completed(error(ErrorCode.INVALID_ARGUMENT, "Invalid player history query.", false));
        }
        if (!available()) {
            return completed(error(ErrorCode.PROVIDER_UNAVAILABLE, "CoreProtect API is unavailable or incompatible.", true));
        }

        PlayerKey key = new PlayerKey(arguments.playerUuid(), arguments.lookbackSeconds());
        if (arguments.cursor() != null) {
            return completed(pageFromCursor(context, key, arguments.cursor(), arguments.limit()));
        }

        return submit(context, () -> {
            Instant windowEnd = clock.instant();
            LookupBatch batch = backend.lookupPlayer(arguments.playerUuid(), arguments.lookbackSeconds());
            return firstPage(
                context,
                key,
                arguments.limit(),
                batch,
                windowEnd.minusSeconds(arguments.lookbackSeconds()),
                windowEnd
            );
        });
    }

    private ToolResult firstPage(
        ToolExecutionContext context,
        QueryKey key,
        int limit,
        LookupBatch batch,
        Instant windowStart,
        Instant windowEnd
    ) {
        if (batch == null) {
            return error(ErrorCode.PROVIDER_UNAVAILABLE, "CoreProtect did not return a lookup result.", true);
        }
        List<BlockChange> allRecords = batch.records();
        int storedCount = Math.min(allRecords.size(), MAX_SNAPSHOT_RECORDS);
        List<BlockChange> storedRecords = List.copyOf(allRecords.subList(0, storedCount));
        boolean cacheLimited = storedCount < allRecords.size();
        Integer totalCount = batch.totalCount();
        if (totalCount == null && !batch.incomplete() && !cacheLimited) {
            totalCount = allRecords.size();
        }

        Snapshot snapshot = new Snapshot(
            key,
            context.requesterUuid(),
            context.sessionId(),
            storedRecords,
            totalCount,
            batch.incomplete() || cacheLimited,
            normalizedSource(batch.source()),
            windowStart,
            windowEnd,
            clock.instant().plus(SNAPSHOT_TTL)
        );
        UUID snapshotId = null;
        if (storedRecords.size() > limit) {
            snapshotId = UUID.randomUUID();
            remember(snapshotId, snapshot);
        }
        return page(snapshotId, snapshot, 0, limit);
    }

    private ToolResult pageFromCursor(
        ToolExecutionContext context,
        QueryKey key,
        String cursor,
        int limit
    ) {
        Cursor parsed = parseCursor(cursor);
        if (parsed == null) {
            return error(ErrorCode.INVALID_ARGUMENT, "History cursor is invalid.", false);
        }
        Snapshot snapshot = findSnapshot(parsed.snapshotId());
        if (snapshot == null
            || !snapshot.key().equals(key)
            || !Objects.equals(snapshot.requesterUuid(), context.requesterUuid())
            || !Objects.equals(snapshot.sessionId(), context.sessionId())
            || parsed.offset() <= 0
            || parsed.offset() >= snapshot.records().size()) {
            return error(ErrorCode.INVALID_ARGUMENT, "History cursor is expired or does not match this query.", false);
        }
        return page(parsed.snapshotId(), snapshot, parsed.offset(), limit);
    }

    private ToolResult page(UUID snapshotId, Snapshot snapshot, int offset, int limit) {
        int end = Math.min(offset + limit, snapshot.records().size());
        List<BlockChange> records = snapshot.records().subList(offset, end);
        boolean hasMoreCached = end < snapshot.records().size();
        String nextCursor = hasMoreCached && snapshotId != null
            ? cursor(snapshotId, end)
            : null;
        boolean truncated = hasMoreCached || snapshot.incomplete();
        HistoryData data = new HistoryData(
            List.copyOf(records),
            records.size(),
            snapshot.totalCount(),
            nextCursor,
            snapshot.windowStart(),
            snapshot.windowEnd()
        );
        return new ToolResult(
            records.isEmpty() ? ResultStatus.EMPTY : ResultStatus.OK,
            data,
            null,
            clock.instant(),
            snapshot.source(),
            truncated
        );
    }

    private <T> CompletionStage<ToolResult> submit(ToolExecutionContext context, QueryWork<ToolResult> work) {
        Instant now = clock.instant();
        Instant deadline = context.deadlineAt();
        if (deadline == null || !deadline.isAfter(now)) {
            return completed(error(ErrorCode.TIMEOUT, "History query deadline has expired.", true));
        }
        long deadlineMillis = Math.max(1L, Duration.between(now, deadline).toMillis());
        long timeoutMillis = Math.min(queryTimeout.toMillis(), deadlineMillis);
        CompletableFuture<ToolResult> result = new CompletableFuture<>();
        AtomicReference<Future<?>> taskReference = new AtomicReference<>();
        ScheduledFuture<?> timeout = timeoutScheduler.schedule(() -> {
            boolean timedOut = result.complete(
                error(ErrorCode.TIMEOUT, "CoreProtect history query timed out.", true)
            );
            if (timedOut) {
                Future<?> task = taskReference.get();
                if (task != null) {
                    task.cancel(true);
                }
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);

        try {
            Future<?> task = lookupExecutor.submit(() -> {
                try {
                    result.complete(work.run());
                } catch (RuntimeException failure) {
                    result.complete(error(ErrorCode.PROVIDER_UNAVAILABLE, "CoreProtect history query failed.", true));
                } finally {
                    timeout.cancel(false);
                }
            });
            taskReference.set(task);
            if (result.isDone()) {
                task.cancel(true);
            }
        } catch (RejectedExecutionException failure) {
            timeout.cancel(false);
            result.complete(error(ErrorCode.BUSY, "CoreProtect lookup queue is full.", true));
        }
        return result;
    }

    private synchronized void remember(UUID id, Snapshot snapshot) {
        pruneSnapshots();
        while (snapshots.size() >= MAX_SNAPSHOTS) {
            UUID eldest = snapshots.keySet().iterator().next();
            snapshots.remove(eldest);
        }
        snapshots.put(id, snapshot);
    }

    private synchronized Snapshot findSnapshot(UUID id) {
        pruneSnapshots();
        return snapshots.get(id);
    }

    private synchronized void pruneSnapshots() {
        Instant now = clock.instant();
        snapshots.values().removeIf(snapshot -> !now.isBefore(snapshot.expiresAt()));
    }

    private boolean available() {
        try {
            return backend.isEnabled() && backend.apiVersion() >= MINIMUM_API_VERSION;
        } catch (LinkageError | RuntimeException failure) {
            return false;
        }
    }

    private static boolean valid(ToolExecutionContext context) {
        return context != null && context.requesterUuid() != null && context.sessionId() != null;
    }

    private static boolean valid(AreaHistoryArguments arguments) {
        if (arguments == null || arguments.center() == null
            || arguments.center().worldId() == null || arguments.center().worldId().isBlank()
            || !Double.isFinite(arguments.center().x()) || !Double.isFinite(arguments.center().y())
            || !Double.isFinite(arguments.center().z()) || !validRadius(arguments.radius())
            || !validLookback(arguments.lookbackSeconds()) || !validPageSize(arguments.limit())) {
            return false;
        }
        return arguments.cursor() == null || arguments.cursor().length() <= 256;
    }

    private static boolean validRadius(int radius) {
        return radius >= 0 && radius <= 64;
    }

    private static boolean validLookback(int lookbackSeconds) {
        return lookbackSeconds >= 1 && lookbackSeconds <= 86_400;
    }

    private static boolean validPageSize(int limit) {
        return limit >= 1 && limit <= MAX_PAGE_SIZE;
    }

    private static String cursor(UUID snapshotId, int offset) {
        return "cp1:" + snapshotId + ":" + offset;
    }

    private static Cursor parseCursor(String value) {
        if (value == null || value.length() > 256 || !value.startsWith("cp1:")) {
            return null;
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            int offset = Integer.parseInt(parts[2]);
            if (offset <= 0) {
                return null;
            }
            return new Cursor(UUID.fromString(parts[1]), offset);
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static String normalizedSource(String source) {
        if (source == null || source.isBlank()) {
            return SOURCE;
        }
        return source.length() <= 128 ? source : source.substring(0, 128);
    }

    private ToolResult error(ErrorCode code, String message, boolean retryable) {
        return ToolResult.error(code, message, retryable, clock.instant(), SOURCE);
    }

    private static CompletionStage<ToolResult> completed(ToolResult result) {
        return CompletableFuture.completedFuture(result);
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return new ThreadFactory() {
            private int sequence;

            @Override
            public synchronized Thread newThread(Runnable task) {
                Thread thread = new Thread(task, prefix + "-" + ++sequence);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    @Override
    public void close() {
        synchronized (this) {
            snapshots.clear();
        }
        if (ownsExecutors) {
            lookupExecutor.shutdownNow();
            timeoutScheduler.shutdownNow();
        }
    }

    public interface Backend {
        boolean isEnabled();

        int apiVersion();

        /** Captures a Provider API location on the caller thread, or returns empty if the world is unloaded. */
        Optional<Object> captureCenter(Location location);

        LookupBatch lookupArea(Object capturedCenter, Location center, int radius, int lookbackSeconds);

        LookupBatch lookupPlayer(UUID playerUuid, int lookbackSeconds);
    }

    public record LookupBatch(
        List<BlockChange> records,
        Integer totalCount,
        boolean incomplete,
        String source
    ) {
        public LookupBatch {
            records = records == null ? List.of() : List.copyOf(records);
        }

        public static LookupBatch complete(List<BlockChange> records, String source) {
            List<BlockChange> safeRecords = records == null ? List.of() : List.copyOf(records);
            return new LookupBatch(safeRecords, safeRecords.size(), false, source);
        }
    }

    private sealed interface QueryKey permits AreaKey, PlayerKey {
    }

    private record AreaKey(Location center, int radius, int lookbackSeconds) implements QueryKey {
    }

    private record PlayerKey(UUID playerUuid, int lookbackSeconds) implements QueryKey {
    }

    private record Cursor(UUID snapshotId, int offset) {
    }

    private record Snapshot(
        QueryKey key,
        UUID requesterUuid,
        UUID sessionId,
        List<BlockChange> records,
        Integer totalCount,
        boolean incomplete,
        String source,
        Instant windowStart,
        Instant windowEnd,
        Instant expiresAt
    ) {
    }

    @FunctionalInterface
    private interface QueryWork<T> {
        T run();
    }
}
