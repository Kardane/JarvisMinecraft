package io.github.kardane.jarvisminecraft.common.audit;

import com.google.gson.Gson;
import io.github.kardane.jarvisminecraft.common.runtime.AuditSink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AsyncJsonlAuditSink implements AuditSink {
    public static final int DEFAULT_RETENTION_DAYS = 7;
    public static final long DEFAULT_MAX_TOTAL_BYTES = 100L * 1024L * 1024L;
    public static final long DEFAULT_MAX_FILE_BYTES = 8L * 1024L * 1024L;
    public static final int DEFAULT_MAX_QUEUE = 512;

    private static final Pattern FILE_PATTERN =
        Pattern.compile("^jarvis-audit-(\\d{4}-\\d{2}-\\d{2})-(\\d{4})\\.jsonl$");

    private final Object lock = new Object();
    private final Path directory;
    private final int retentionDays;
    private final long maxTotalBytes;
    private final long maxFileBytes;
    private final int maxQueue;
    private final Clock clock;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final Gson gson = new Gson();
    private final ArrayDeque<PendingRecord> queue = new ArrayDeque<>();

    private boolean drainRunning;
    private boolean closed;
    private boolean writable = true;
    private long rejectedRecords;
    private Instant lastSuccessfulWriteAt;
    private Instant lastErrorAt;
    private String lastErrorCode;
    private long totalBytes;
    private int fileCount;
    private CompletableFuture<Void> closeFuture;

    public AsyncJsonlAuditSink(Path directory, Clock clock) {
        this(
            directory,
            DEFAULT_RETENTION_DAYS,
            DEFAULT_MAX_TOTAL_BYTES,
            DEFAULT_MAX_FILE_BYTES,
            DEFAULT_MAX_QUEUE,
            clock,
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "jarvis-audit-writer");
                thread.setDaemon(true);
                return thread;
            }),
            true
        );
    }

    public AsyncJsonlAuditSink(
        Path directory,
        int retentionDays,
        long maxTotalBytes,
        long maxFileBytes,
        int maxQueue,
        Clock clock,
        ExecutorService executor
    ) {
        this(
            directory,
            retentionDays,
            maxTotalBytes,
            maxFileBytes,
            maxQueue,
            clock,
            executor,
            false
        );
    }

    private AsyncJsonlAuditSink(
        Path directory,
        int retentionDays,
        long maxTotalBytes,
        long maxFileBytes,
        int maxQueue,
        Clock clock,
        ExecutorService executor,
        boolean ownsExecutor
    ) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;

        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays must be positive.");
        }
        if (maxFileBytes < 1_024) {
            throw new IllegalArgumentException("maxFileBytes must be at least 1024.");
        }
        if (maxTotalBytes < maxFileBytes * 2) {
            throw new IllegalArgumentException(
                "maxTotalBytes must be at least twice maxFileBytes."
            );
        }
        if (maxQueue < 1) {
            throw new IllegalArgumentException("maxQueue must be positive.");
        }

        this.retentionDays = retentionDays;
        this.maxTotalBytes = maxTotalBytes;
        this.maxFileBytes = maxFileBytes;
        this.maxQueue = maxQueue;
    }

    @Override
    public CompletionStage<Boolean> record(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        CompletableFuture<Boolean> result = new CompletableFuture<>();

        synchronized (lock) {
            if (closed) {
                rejectLocked("AUDIT_CLOSED", false);
                result.complete(false);
                return result;
            }
            if (queue.size() >= maxQueue) {
                rejectLocked("AUDIT_QUEUE_FULL", true);
                result.complete(false);
                return result;
            }

            queue.addLast(new PendingRecord(event, result));
            if (!drainRunning) {
                drainRunning = true;
                try {
                    executor.execute(this::drain);
                } catch (RuntimeException failure) {
                    drainRunning = false;
                    failQueuedLocked("AUDIT_EXECUTOR_REJECTED", false);
                }
            }
        }
        return result;
    }

    public Health health() {
        synchronized (lock) {
            Status status = Status.HEALTHY;
            if (!writable) {
                status = Status.UNHEALTHY;
            } else if (
                closed
                    || queue.size() >= Math.max(1, (int) Math.floor(maxQueue * 0.8))
                    || lastErrorCode != null
            ) {
                status = Status.DEGRADED;
            }

            return new Health(
                status,
                writable,
                closed,
                queue.size(),
                maxQueue,
                rejectedRecords,
                lastSuccessfulWriteAt,
                lastErrorAt,
                lastErrorCode,
                totalBytes,
                fileCount,
                retentionDays,
                maxTotalBytes,
                maxFileBytes
            );
        }
    }

    public CompletionStage<Void> closeAsync() {
        synchronized (lock) {
            if (closeFuture != null) {
                return closeFuture;
            }
            closed = true;
            closeFuture = new CompletableFuture<>();
            if (!drainRunning && queue.isEmpty()) {
                finishCloseLocked();
            }
            return closeFuture;
        }
    }

    private void drain() {
        while (true) {
            PendingRecord pending;
            synchronized (lock) {
                pending = queue.pollFirst();
                if (pending == null) {
                    drainRunning = false;
                    if (closed) {
                        finishCloseLocked();
                    }
                    return;
                }
            }

            try {
                write(pending.event());
                synchronized (lock) {
                    writable = true;
                    lastSuccessfulWriteAt = clock.instant();
                    lastErrorAt = null;
                    lastErrorCode = null;
                }
                pending.result().complete(true);
            } catch (Exception failure) {
                synchronized (lock) {
                    rejectLocked("AUDIT_IO_ERROR", false);
                }
                pending.result().complete(false);
            }
        }
    }

    private void write(AuditEvent event) throws IOException {
        String line = gson.toJson(sanitizeEvent(event)) + "\n";
        long lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
        if (lineBytes > maxFileBytes) {
            synchronized (lock) {
                rejectLocked("AUDIT_RECORD_TOO_LARGE", true);
            }
            throw new IOException("Audit record exceeds maxFileBytes.");
        }

        Files.createDirectories(directory);

        Instant now = clock.instant();
        List<AuditFile> files = auditFiles();
        files = deleteExpired(files, now);

        Target target = chooseTarget(files, lineBytes, now);
        String protectedName = target.existing() ? target.name() : null;
        files = enforceTotalLimit(files, lineBytes, protectedName);

        long totalBefore = files.stream().mapToLong(AuditFile::size).sum();
        if (totalBefore + lineBytes > maxTotalBytes) {
            synchronized (lock) {
                rejectLocked("AUDIT_TOTAL_LIMIT", true);
            }
            throw new IOException("Audit total-byte limit cannot accommodate record.");
        }

        Path targetPath = directory.resolve(target.name());
        Files.writeString(
            targetPath,
            line,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );

        boolean existed = files.stream()
            .anyMatch(file -> file.name().equals(target.name()));
        synchronized (lock) {
            totalBytes = totalBefore + lineBytes;
            fileCount = files.size() + (existed ? 0 : 1);
        }
    }

    private Map<String, Object> sanitizeEvent(AuditEvent event) {
        LinkedHashMap<String, Object> object = new LinkedHashMap<>();
        object.put("timestamp", event.timestamp().toString());
        object.put("serverId", event.serverId());
        object.put("requesterUuid", event.requesterUuid().toString());
        object.put("requestId", event.requestId().toString());
        object.put("toolCallId", event.toolCallId().toString());
        object.put(
            "actionId",
            event.actionId() == null ? null : event.actionId().toString()
        );
        object.put("tool", event.tool().wireName());
        object.put("risk", event.risk().name());
        object.put(
            "argumentSummary",
            AuditMasker.sanitize(event.argumentSummary())
        );
        object.put("outcome", AuditMasker.sanitize(event.outcome()));
        object.put("source", AuditMasker.sanitize(event.source()));
        object.put("latencyMillis", event.latencyMillis());
        object.put("modelId", AuditMasker.sanitize(event.modelId()));
        object.put(
            "fallbackReason",
            event.fallbackReason() == null
                ? null
                : AuditMasker.sanitize(event.fallbackReason())
        );
        return object;
    }

    private List<AuditFile> auditFiles() throws IOException {
        if (!Files.exists(directory)) {
            return new ArrayList<>();
        }

        List<AuditFile> files = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            for (Path path : stream.toList()) {
                Matcher matcher = FILE_PATTERN.matcher(path.getFileName().toString());
                if (!matcher.matches() || !Files.isRegularFile(path)) {
                    continue;
                }
                BasicFileAttributes attrs =
                    Files.readAttributes(path, BasicFileAttributes.class);
                files.add(
                    new AuditFile(
                        path.getFileName().toString(),
                        attrs.size(),
                        attrs.lastModifiedTime().toInstant()
                    )
                );
            }
        }
        files.sort(
            Comparator.comparing(AuditFile::modifiedAt)
                .thenComparing(AuditFile::name)
        );
        return files;
    }

    private List<AuditFile> deleteExpired(
        List<AuditFile> files,
        Instant now
    ) throws IOException {
        Instant cutoff = now.minusSeconds(retentionDays * 24L * 60L * 60L);
        List<AuditFile> kept = new ArrayList<>();
        for (AuditFile file : files) {
            if (file.modifiedAt().isBefore(cutoff)) {
                Files.deleteIfExists(directory.resolve(file.name()));
            } else {
                kept.add(file);
            }
        }
        return kept;
    }

    private Target chooseTarget(
        List<AuditFile> files,
        long incomingBytes,
        Instant now
    ) {
        String day = LocalDate.ofInstant(now, ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_LOCAL_DATE);

        AuditFile last = null;
        int lastSequence = 0;
        for (AuditFile file : files) {
            Matcher matcher = FILE_PATTERN.matcher(file.name());
            if (!matcher.matches() || !day.equals(matcher.group(1))) {
                continue;
            }
            int sequence = Integer.parseInt(matcher.group(2));
            if (last == null || sequence > lastSequence) {
                last = file;
                lastSequence = sequence;
            }
        }

        if (last != null && last.size() + incomingBytes <= maxFileBytes) {
            return new Target(last.name(), true);
        }

        int next = last == null ? 1 : lastSequence + 1;
        return new Target(
            "jarvis-audit-"
                + day
                + "-"
                + String.format("%04d", next)
                + ".jsonl",
            false
        );
    }

    private List<AuditFile> enforceTotalLimit(
        List<AuditFile> files,
        long incomingBytes,
        String protectedName
    ) throws IOException {
        List<AuditFile> kept = new ArrayList<>(files);
        long total = kept.stream().mapToLong(AuditFile::size).sum();

        while (total + incomingBytes > maxTotalBytes) {
            int index = -1;
            for (int cursor = 0; cursor < kept.size(); cursor += 1) {
                if (!kept.get(cursor).name().equals(protectedName)) {
                    index = cursor;
                    break;
                }
            }
            if (index < 0) {
                break;
            }
            AuditFile oldest = kept.remove(index);
            Files.deleteIfExists(directory.resolve(oldest.name()));
            total -= oldest.size();
        }
        return kept;
    }

    private void rejectLocked(String code, boolean remainsWritable) {
        rejectedRecords += 1;
        lastErrorAt = clock.instant();
        lastErrorCode = code;
        writable = remainsWritable;
    }

    private void failQueuedLocked(String code, boolean remainsWritable) {
        rejectLocked(code, remainsWritable);
        PendingRecord pending;
        while ((pending = queue.pollFirst()) != null) {
            pending.result().complete(false);
        }
        if (closed) {
            finishCloseLocked();
        }
    }

    private void finishCloseLocked() {
        if (ownsExecutor) {
            executor.shutdown();
        }
        if (closeFuture != null && !closeFuture.isDone()) {
            closeFuture.complete(null);
        }
    }

    public enum Status {
        HEALTHY,
        DEGRADED,
        UNHEALTHY
    }

    public record Health(
        Status status,
        boolean writable,
        boolean closed,
        int queueDepth,
        int maxQueue,
        long rejectedRecords,
        Instant lastSuccessfulWriteAt,
        Instant lastErrorAt,
        String lastErrorCode,
        long totalBytes,
        int fileCount,
        int retentionDays,
        long maxTotalBytes,
        long maxFileBytes
    ) {
    }

    private record PendingRecord(
        AuditEvent event,
        CompletableFuture<Boolean> result
    ) {
    }

    private record AuditFile(
        String name,
        long size,
        Instant modifiedAt
    ) {
    }

    private record Target(String name, boolean existing) {
    }
}
