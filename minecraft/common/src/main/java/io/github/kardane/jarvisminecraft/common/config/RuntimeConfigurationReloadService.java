package io.github.kardane.jarvisminecraft.common.config;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RuntimeConfigurationReloadService
    implements AutoCloseable {
    private final RuntimeConfigurationManager runtimeConfiguration;
    private final ExecutorService executor;
    private final AtomicBoolean closed =
        new AtomicBoolean();

    public RuntimeConfigurationReloadService(
        RuntimeConfigurationManager runtimeConfiguration
    ) {
        this(
            runtimeConfiguration,
            Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(
                        runnable,
                        "jarvis-config-reload"
                    );
                    thread.setDaemon(true);
                    return thread;
                }
            )
        );
    }

    RuntimeConfigurationReloadService(
        RuntimeConfigurationManager runtimeConfiguration,
        ExecutorService executor
    ) {
        this.runtimeConfiguration = Objects.requireNonNull(
            runtimeConfiguration,
            "runtimeConfiguration"
        );
        this.executor = Objects.requireNonNull(
            executor,
            "executor"
        );
    }

    public CompletionStage<
        RuntimeConfigurationManager.ReloadResult
    > reloadAsync() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException(
                    "Runtime configuration reload service is closed."
                )
            );
        }

        try {
            return CompletableFuture.supplyAsync(
                runtimeConfiguration::reload,
                executor
            );
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(
                failure
            );
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }
}
