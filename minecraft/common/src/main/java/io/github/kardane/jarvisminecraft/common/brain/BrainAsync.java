package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;

final class BrainAsync {
    private BrainAsync() {
    }

    static <T> CompletionStage<T> withDeadline(
        CompletionStage<T> stage,
        Instant deadlineAt,
        ErrorCode code,
        String message,
        Clock clock
    ) {
        long delay = Math.max(
            0L,
            Duration.between(clock.instant(), deadlineAt).toMillis()
        );
        if (delay == 0L) {
            return CompletableFuture.failedFuture(
                new ProtocolException(code, message)
            );
        }

        CompletableFuture<T> output = new CompletableFuture<>();
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                output.complete(value);
            } else {
                output.completeExceptionally(unwrap(failure));
            }
        });
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
            .execute(
                () -> output.completeExceptionally(
                    new ProtocolException(code, message)
                )
            );
        return output;
    }

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (
            current instanceof CompletionException
                && current.getCause() != null
        ) {
            current = current.getCause();
        }
        return current;
    }

    static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    static long elapsedMillis(Instant startedAt, Clock clock) {
        return Math.max(
            0L,
            Duration.between(startedAt, clock.instant()).toMillis()
        );
    }

    static String failureCode(
        Throwable failure,
        String fallback,
        String timeout
    ) {
        if (failure == null) {
            return fallback;
        }
        Throwable cause = unwrap(failure);
        if (
            cause instanceof ProtocolException protocol
                && protocol.code() == ErrorCode.TIMEOUT
        ) {
            return timeout;
        }
        return fallback;
    }
}
