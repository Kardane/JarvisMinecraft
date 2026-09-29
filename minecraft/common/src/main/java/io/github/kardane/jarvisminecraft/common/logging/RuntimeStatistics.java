package io.github.kardane.jarvisminecraft.common.logging;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

public final class RuntimeStatistics {
    private static final int LATENCY_SAMPLE_LIMIT = 4_096;

    private final Instant startedAt;

    private final LongAdder requestsAccepted = new LongAdder();
    private final LongAdder requestsSucceeded = new LongAdder();
    private final LongAdder requestsFailed = new LongAdder();
    private final LongAdder requestLatencyMillis = new LongAdder();
    private final Object requestLatencyLock = new Object();
    private final ArrayDeque<Long> requestLatencySamples =
        new ArrayDeque<>();

    private final LongAdder lunaInputTokens = new LongAdder();
    private final LongAdder lunaOutputTokens = new LongAdder();
    private final LongAdder lunaTotalTokens = new LongAdder();
    private final LongAdder lunaRounds = new LongAdder();
    private final LongAdder lunaLatencyMillis = new LongAdder();
    private final LongAdder webSearchCalls = new LongAdder();

    private final LongAdder jevCompleted = new LongAdder();
    private final LongAdder jevFailed = new LongAdder();
    private final LongAdder jevFallbacks = new LongAdder();
    private final LongAdder jevLatencyMillis = new LongAdder();
    private final DoubleAdder reasoningConfidence = new DoubleAdder();
    private final LongAdder reasoningConfidenceCount = new LongAdder();

    private final LongAdder toolCalls = new LongAdder();
    private final LongAdder toolFailures = new LongAdder();
    private final LongAdder toolDenied = new LongAdder();

    private final ConcurrentHashMap<String, LongAdder> jevReasoningCounts =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> resolvedReasoningCounts =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> routeCounts =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> toolCounts =
        new ConcurrentHashMap<>();

    public RuntimeStatistics(Instant startedAt) {
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    public void record(String event, Map<String, ?> fields) {
        if (event == null) {
            return;
        }
        Map<String, ?> safeFields =
            fields == null ? Map.of() : fields;

        switch (event) {
            case JarvisEvents.REQUEST_ACCEPTED ->
                requestsAccepted.increment();
            case JarvisEvents.REQUEST_COMPLETED -> {
                requestsSucceeded.increment();
                recordRequestLatency(
                    longValue(safeFields.get("latencyMs"))
                );
                if (booleanValue(
                    safeFields.get("usageComplete")
                )) {
                    lunaInputTokens.add(longValue(
                        safeFields.get("inputTokens")
                    ));
                    lunaOutputTokens.add(longValue(
                        safeFields.get("outputTokens")
                    ));
                    lunaTotalTokens.add(longValue(
                        safeFields.get("totalTokens")
                    ));
                }
            }
            case JarvisEvents.REQUEST_FAILED -> {
                requestsFailed.increment();
                recordRequestLatency(
                    longValue(safeFields.get("latencyMs"))
                );
            }
            case JarvisEvents.JEV_COMPLETED -> {
                jevCompleted.increment();
                jevLatencyMillis.add(longValue(
                    safeFields.get("latencyMs")
                ));
                increment(
                    jevReasoningCounts,
                    stringValue(safeFields.get("reasoning"))
                );
                double confidence = doubleValue(
                    safeFields.get("reasoningConfidence")
                );
                if (Double.isFinite(confidence)) {
                    reasoningConfidence.add(confidence);
                    reasoningConfidenceCount.increment();
                }
            }
            case JarvisEvents.JEV_FAILED -> {
                jevFailed.increment();
                jevLatencyMillis.add(longValue(
                    safeFields.get("latencyMs")
                ));
            }
            case JarvisEvents.JEV_FALLBACK ->
                jevFallbacks.increment();
            case JarvisEvents.ROUTING_RESOLVED ->
                increment(
                    routeCounts,
                    stringValue(safeFields.get("route"))
                );
            case JarvisEvents.REASONING_RESOLVED ->
                increment(
                    resolvedReasoningCounts,
                    stringValue(safeFields.get("reasoning"))
                );
            case JarvisEvents.LUNA_ROUND_COMPLETED -> {
                lunaRounds.increment();
                lunaLatencyMillis.add(longValue(
                    safeFields.get("latencyMs")
                ));
                webSearchCalls.add(longValue(
                    safeFields.get("webSearchCallCount")
                ));
            }
            case JarvisEvents.TOOL_COMPLETED -> {
                toolCalls.increment();
                String tool = stringValue(
                    safeFields.get("tool")
                );
                increment(toolCounts, tool);
                String outcome = stringValue(
                    safeFields.get("outcome")
                );
                if (
                    outcome != null
                        && !"OK".equals(outcome)
                        && !"EMPTY".equals(outcome)
                ) {
                    toolFailures.increment();
                }
            }
            case JarvisEvents.TOOL_DENIED ->
                toolDenied.increment();
            default -> {
                // Not a statistics-bearing event.
            }
        }
    }

    public Snapshot snapshot() {
        long succeeded = requestsSucceeded.sum();
        long failed = requestsFailed.sum();
        long requestCompleted = succeeded + failed;
        long jevSuccess = jevCompleted.sum();
        long jevError = jevFailed.sum();
        long jevAttempts = jevSuccess + jevError;

        return new Snapshot(
            startedAt,
            requestsAccepted.sum(),
            succeeded,
            failed,
            requestCompleted == 0
                ? 0.0
                : requestLatencyMillis.sum()
                    / (double) requestCompleted,
            requestP95Millis(),
            requestCompleted == 0
                ? 0.0
                : succeeded / (double) requestCompleted,
            lunaInputTokens.sum(),
            lunaOutputTokens.sum(),
            lunaTotalTokens.sum(),
            lunaRounds.sum(),
            lunaRounds.sum() == 0
                ? 0.0
                : lunaLatencyMillis.sum()
                    / (double) lunaRounds.sum(),
            webSearchCalls.sum(),
            jevSuccess,
            jevError,
            jevFallbacks.sum(),
            jevAttempts == 0
                ? 0.0
                : jevLatencyMillis.sum()
                    / (double) jevAttempts,
            reasoningConfidenceCount.sum() == 0
                ? 0.0
                : reasoningConfidence.sum()
                    / reasoningConfidenceCount.sum(),
            snapshot(jevReasoningCounts),
            snapshot(resolvedReasoningCounts),
            snapshot(routeCounts),
            toolCalls.sum(),
            toolFailures.sum(),
            toolDenied.sum(),
            snapshot(toolCounts)
        );
    }

    private void recordRequestLatency(long latencyMillis) {
        requestLatencyMillis.add(latencyMillis);
        synchronized (requestLatencyLock) {
            if (
                requestLatencySamples.size()
                    >= LATENCY_SAMPLE_LIMIT
            ) {
                requestLatencySamples.removeFirst();
            }
            requestLatencySamples.addLast(latencyMillis);
        }
    }

    private long requestP95Millis() {
        List<Long> sorted;
        synchronized (requestLatencyLock) {
            if (requestLatencySamples.isEmpty()) {
                return 0L;
            }
            sorted = new ArrayList<>(requestLatencySamples);
        }
        sorted.sort(Long::compareTo);
        int index =
            (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(
            Math.max(
                0,
                Math.min(index, sorted.size() - 1)
            )
        );
    }

    private void increment(
        ConcurrentHashMap<String, LongAdder> values,
        String key
    ) {
        if (key == null || key.isBlank()) {
            return;
        }
        values.computeIfAbsent(
            key,
            ignored -> new LongAdder()
        ).increment();
    }

    private Map<String, Long> snapshot(
        ConcurrentHashMap<String, LongAdder> values
    ) {
        LinkedHashMap<String, Long> output =
            new LinkedHashMap<>();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry ->
                output.put(
                    entry.getKey(),
                    entry.getValue().sum()
                )
            );
        return Map.copyOf(output);
    }

    private long longValue(Object value) {
        return value instanceof Number number
            ? Math.max(0L, number.longValue())
            : 0L;
    }

    private double doubleValue(Object value) {
        return value instanceof Number number
            ? number.doubleValue()
            : Double.NaN;
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record Snapshot(
        Instant startedAt,
        long requestsAccepted,
        long requestsSucceeded,
        long requestsFailed,
        double averageRequestLatencyMillis,
        long p95RequestLatencyMillis,
        double requestSuccessRate,
        long lunaInputTokens,
        long lunaOutputTokens,
        long lunaTotalTokens,
        long lunaRounds,
        double averageLunaRoundLatencyMillis,
        long webSearchCalls,
        long jevCompleted,
        long jevFailed,
        long jevFallbacks,
        double averageJevLatencyMillis,
        double averageReasoningConfidence,
        Map<String, Long> jevReasoningCounts,
        Map<String, Long> resolvedReasoningCounts,
        Map<String, Long> routeCounts,
        long toolCalls,
        long toolFailures,
        long toolDenied,
        Map<String, Long> toolCounts
    ) {
        public Snapshot {
            Objects.requireNonNull(startedAt, "startedAt");
            jevReasoningCounts = Map.copyOf(
                Objects.requireNonNull(
                    jevReasoningCounts,
                    "jevReasoningCounts"
                )
            );
            resolvedReasoningCounts = Map.copyOf(
                Objects.requireNonNull(
                    resolvedReasoningCounts,
                    "resolvedReasoningCounts"
                )
            );
            routeCounts = Map.copyOf(
                Objects.requireNonNull(
                    routeCounts,
                    "routeCounts"
                )
            );
            toolCounts = Map.copyOf(
                Objects.requireNonNull(
                    toolCounts,
                    "toolCounts"
                )
            );
        }

        public long resolvedReasoningTotal() {
            return resolvedReasoningCounts.values().stream()
                .mapToLong(Long::longValue)
                .sum();
        }

        public long routeTotal() {
            return routeCounts.values().stream()
                .mapToLong(Long::longValue)
                .sum();
        }
    }
}
