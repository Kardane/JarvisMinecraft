package io.github.kardane.jarvisminecraft.common.brain;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProgressNotifier {
    public ProgressHandle schedule(
        Duration threshold,
        Runnable notification
    ) {
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(notification, "notification");
        if (threshold.isNegative()) {
            throw new IllegalArgumentException(
                "threshold must not be negative."
            );
        }

        Handle handle = new Handle();
        CompletableFuture.delayedExecutor(
            threshold.toMillis(),
            TimeUnit.MILLISECONDS
        ).execute(() -> {
            if (!handle.completed()) {
                notification.run();
            }
        });
        return handle;
    }

    public interface ProgressHandle {
        void complete();

        boolean completed();
    }

    private static final class Handle
        implements ProgressHandle {
        private final AtomicBoolean completed =
            new AtomicBoolean();

        @Override
        public void complete() {
            completed.set(true);
        }

        @Override
        public boolean completed() {
            return completed.get();
        }
    }
}
