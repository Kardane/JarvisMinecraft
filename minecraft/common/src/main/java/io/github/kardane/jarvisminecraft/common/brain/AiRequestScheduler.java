package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

public final class AiRequestScheduler {
    public static final int DEFAULT_MAX_CONCURRENT = 4;
    public static final int DEFAULT_MAX_QUEUED_PER_SESSION = 2;
    public static final int DEFAULT_MAX_QUEUED_TOTAL = 16;

    private final Executor executor;
    private final int maxConcurrent;
    private final int maxQueuedPerSession;
    private final int maxQueuedTotal;
    private final Set<SessionKey> activeSessions = new HashSet<>();
    private final Map<SessionKey, Integer> queuedBySession = new HashMap<>();
    private final ArrayDeque<QueueItem<?>> queue = new ArrayDeque<>();

    private int activeCount;
    private boolean shutdown;

    public AiRequestScheduler(Executor executor) {
        this(
            executor,
            DEFAULT_MAX_CONCURRENT,
            DEFAULT_MAX_QUEUED_PER_SESSION,
            DEFAULT_MAX_QUEUED_TOTAL
        );
    }

    public AiRequestScheduler(
        Executor executor,
        int maxConcurrent,
        int maxQueuedPerSession,
        int maxQueuedTotal
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        if (maxConcurrent < 1 || maxQueuedPerSession < 0 || maxQueuedTotal < 0) {
            throw new IllegalArgumentException("AI scheduler limits are invalid.");
        }
        this.maxConcurrent = maxConcurrent;
        this.maxQueuedPerSession = maxQueuedPerSession;
        this.maxQueuedTotal = maxQueuedTotal;
    }

    public <T> CompletionStage<T> submit(
        UUID requesterUuid,
        UUID sessionId,
        Supplier<? extends CompletionStage<T>> job
    ) {
        Objects.requireNonNull(job, "job");
        SessionKey key = new SessionKey(
            Objects.requireNonNull(requesterUuid, "requesterUuid"),
            Objects.requireNonNull(sessionId, "sessionId")
        );
        CompletableFuture<T> result = new CompletableFuture<>();
        QueueItem<T> item = new QueueItem<>(key, job, result);

        synchronized (this) {
            if (shutdown) {
                result.completeExceptionally(cancelled("AI scheduler is shut down."));
                return result;
            }
            if (canStartImmediately(key)) {
                startLocked(item);
                return result;
            }

            int sessionQueued = queuedBySession.getOrDefault(key, 0);
            if (sessionQueued >= maxQueuedPerSession) {
                result.completeExceptionally(busy("Session AI queue is full."));
                return result;
            }
            if (queue.size() >= maxQueuedTotal) {
                result.completeExceptionally(busy("Global AI queue is full."));
                return result;
            }

            queue.addLast(item);
            queuedBySession.put(key, sessionQueued + 1);
        }
        return result;
    }

    public synchronized void cancelSession(UUID requesterUuid, UUID sessionId) {
        SessionKey target = new SessionKey(
            Objects.requireNonNull(requesterUuid, "requesterUuid"),
            Objects.requireNonNull(sessionId, "sessionId")
        );
        cancelQueued(key -> key.equals(target), "Queued request was cancelled with its session.");
    }

    public synchronized void cancelActor(UUID requesterUuid) {
        Objects.requireNonNull(requesterUuid, "requesterUuid");
        cancelQueued(
            key -> key.requesterUuid().equals(requesterUuid),
            "Queued request was cancelled with its actor."
        );
    }

    public synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        cancelQueued(key -> true, "Queued request was cancelled during shutdown.");
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(queue.size(), activeCount, activeSessions.size(), shutdown);
    }

    private boolean canStartImmediately(SessionKey key) {
        return activeCount < maxConcurrent
            && !activeSessions.contains(key)
            && queuedBySession.getOrDefault(key, 0) == 0;
    }

    private <T> void startLocked(QueueItem<T> item) {
        activeCount += 1;
        activeSessions.add(item.key());
        try {
            executor.execute(() -> {
                CompletionStage<T> stage;
                try {
                    stage = Objects.requireNonNull(
                        item.job().get(),
                        "AI scheduler job returned null CompletionStage."
                    );
                } catch (Throwable failure) {
                    item.result().completeExceptionally(failure);
                    finish(item.key());
                    return;
                }

                stage.whenComplete((value, failure) -> {
                    if (failure == null) {
                        item.result().complete(value);
                    } else {
                        item.result().completeExceptionally(failure);
                    }
                    finish(item.key());
                });
            });
        } catch (RuntimeException failure) {
            activeCount -= 1;
            activeSessions.remove(item.key());
            item.result().completeExceptionally(failure);
            dispatchLocked();
        }
    }

    private void finish(SessionKey key) {
        synchronized (this) {
            activeCount -= 1;
            activeSessions.remove(key);
            dispatchLocked();
        }
    }

    private void dispatchLocked() {
        if (shutdown) {
            return;
        }
        boolean progressed;
        do {
            progressed = false;
            if (activeCount >= maxConcurrent) {
                return;
            }
            Iterator<QueueItem<?>> iterator = queue.iterator();
            while (iterator.hasNext()) {
                QueueItem<?> item = iterator.next();
                if (activeSessions.contains(item.key())) {
                    continue;
                }
                iterator.remove();
                decrementQueued(item.key());
                startUnchecked(item);
                progressed = true;
                break;
            }
        } while (progressed && activeCount < maxConcurrent);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void startUnchecked(QueueItem<?> item) {
        startLocked((QueueItem) item);
    }

    private void cancelQueued(
        java.util.function.Predicate<SessionKey> predicate,
        String message
    ) {
        Iterator<QueueItem<?>> iterator = queue.iterator();
        while (iterator.hasNext()) {
            QueueItem<?> item = iterator.next();
            if (!predicate.test(item.key())) {
                continue;
            }
            iterator.remove();
            decrementQueued(item.key());
            item.result().completeExceptionally(cancelled(message));
        }
    }

    private void decrementQueued(SessionKey key) {
        int count = queuedBySession.getOrDefault(key, 0);
        if (count <= 1) {
            queuedBySession.remove(key);
        } else {
            queuedBySession.put(key, count - 1);
        }
    }

    private ProtocolException busy(String message) {
        return new ProtocolException(ErrorCode.BUSY, message);
    }

    private ProtocolException cancelled(String message) {
        return new ProtocolException(ErrorCode.CANCELLED, message);
    }

    public record Snapshot(
        int queuedTotal,
        int activeRequests,
        int activeSessions,
        boolean shutdown
    ) {
    }

    private record SessionKey(UUID requesterUuid, UUID sessionId) {
    }

    private record QueueItem<T>(
        SessionKey key,
        Supplier<? extends CompletionStage<T>> job,
        CompletableFuture<T> result
    ) {
    }
}
