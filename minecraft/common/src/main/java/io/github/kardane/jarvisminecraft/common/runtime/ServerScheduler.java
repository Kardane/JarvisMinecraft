package io.github.kardane.jarvisminecraft.common.runtime;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

@FunctionalInterface
public interface ServerScheduler {
    /**
     * Runs the supplier on the platform's safe server execution context.
     * The returned stage may complete asynchronously after the supplier has returned.
     */
    <T> CompletionStage<T> submit(Supplier<CompletionStage<T>> task);
}
