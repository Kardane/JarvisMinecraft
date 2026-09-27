package io.github.kardane.jarvisminecraft.common.runtime;

import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ScheduleActionArguments;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScheduledActionService
    implements AutoCloseable {
    private static final int MAX_PENDING = 256;
    private static final int MAX_PER_ACTOR = 16;

    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final Map<UUID, Entry> entries =
        new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ScheduledActionService(Clock clock) {
        this(
            clock,
            Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(
                        runnable,
                        "jarvis-scheduled-actions"
                    );
                    thread.setDaemon(true);
                    return thread;
                }
            )
        );
    }

    ScheduledActionService(
        Clock clock,
        ScheduledExecutorService executor
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(
            executor,
            "executor"
        );
    }

    public Snapshot schedule(
        UUID requesterUuid,
        ScheduleActionArguments arguments,
        ActionRunner runner
    ) {
        Objects.requireNonNull(
            requesterUuid,
            "requesterUuid"
        );
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(runner, "runner");
        if (closed.get()) {
            throw new IllegalStateException(
                "Scheduled action service is closed."
            );
        }
        if (entries.size() >= MAX_PENDING) {
            throw new IllegalStateException(
                "Scheduled action capacity is full."
            );
        }
        long actorCount = entries.values().stream()
            .filter(
                entry -> entry.requesterUuid.equals(
                    requesterUuid
                )
            )
            .count();
        if (actorCount >= MAX_PER_ACTOR) {
            throw new IllegalStateException(
                "Requester has too many pending scheduled actions."
            );
        }

        UUID scheduleId = UUID.randomUUID();
        Instant now = clock.instant();
        Instant firstRunAt =
            now.plusSeconds(arguments.delaySeconds());
        Instant expiresAt =
            arguments.durationSeconds() == null
                ? firstRunAt
                : firstRunAt.plusSeconds(
                    arguments.durationSeconds()
                );

        Entry entry = new Entry(
            scheduleId,
            requesterUuid,
            arguments,
            runner,
            firstRunAt,
            expiresAt
        );
        entries.put(scheduleId, entry);
        entry.scheduleAfter(arguments.delaySeconds());

        return new Snapshot(
            scheduleId,
            firstRunAt,
            expiresAt
        );
    }

    public boolean cancel(
        UUID requesterUuid,
        UUID scheduleId
    ) {
        Objects.requireNonNull(
            requesterUuid,
            "requesterUuid"
        );
        Objects.requireNonNull(scheduleId, "scheduleId");
        Entry entry = entries.get(scheduleId);
        if (
            entry == null
                || !entry.requesterUuid.equals(
                    requesterUuid
                )
        ) {
            return false;
        }
        return entry.cancel();
    }

    public List<UUID> cancelActor(UUID requesterUuid) {
        Objects.requireNonNull(
            requesterUuid,
            "requesterUuid"
        );
        List<UUID> cancelled = new ArrayList<>();
        entries.values().stream()
            .filter(
                entry -> entry.requesterUuid.equals(
                    requesterUuid
                )
            )
            .toList()
            .forEach(entry -> {
                if (entry.cancel()) {
                    cancelled.add(entry.scheduleId);
                }
            });
        return List.copyOf(cancelled);
    }

    public List<UUID> pendingScheduleIds() {
        return List.copyOf(entries.keySet());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        entries.values().forEach(Entry::cancel);
        entries.clear();
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface ActionRunner {
        CompletionStage<Boolean> run(
            UUID scheduleId,
            int runIndex
        );
    }

    public record Snapshot(
        UUID scheduleId,
        Instant firstRunAt,
        Instant expiresAt
    ) {
    }

    private final class Entry {
        private final UUID scheduleId;
        private final UUID requesterUuid;
        private final ScheduleActionArguments arguments;
        private final ActionRunner runner;
        private final Instant firstRunAt;
        private final Instant expiresAt;
        private final AtomicBoolean cancelled =
            new AtomicBoolean();
        private volatile ScheduledFuture<?> future;
        private int runIndex;

        private Entry(
            UUID scheduleId,
            UUID requesterUuid,
            ScheduleActionArguments arguments,
            ActionRunner runner,
            Instant firstRunAt,
            Instant expiresAt
        ) {
            this.scheduleId = scheduleId;
            this.requesterUuid = requesterUuid;
            this.arguments = arguments;
            this.runner = runner;
            this.firstRunAt = firstRunAt;
            this.expiresAt = expiresAt;
        }

        private void scheduleAfter(long delaySeconds) {
            future = executor.schedule(
                this::runOnce,
                delaySeconds,
                TimeUnit.SECONDS
            );
        }

        private void runOnce() {
            if (cancelled.get() || closed.get()) {
                finish();
                return;
            }

            int currentRun = runIndex++;
            CompletionStage<Boolean> stage;
            try {
                stage = runner.run(
                    scheduleId,
                    currentRun
                );
            } catch (RuntimeException failure) {
                finish();
                return;
            }

            stage.whenComplete((keepGoing, failure) -> {
                if (
                    failure != null
                        || !Boolean.TRUE.equals(keepGoing)
                        || cancelled.get()
                        || closed.get()
                ) {
                    finish();
                    return;
                }

                Integer interval =
                    arguments.intervalSeconds();
                if (interval == null) {
                    finish();
                    return;
                }

                Instant next =
                    clock.instant().plusSeconds(interval);
                if (next.isAfter(expiresAt)) {
                    finish();
                    return;
                }
                scheduleAfter(interval);
            });
        }

        private boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            ScheduledFuture<?> current = future;
            if (current != null) {
                current.cancel(false);
            }
            finish();
            return true;
        }

        private void finish() {
            entries.remove(scheduleId, this);
        }
    }
}
