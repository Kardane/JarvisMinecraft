package io.github.kardane.jarvisminecraft.common.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class ConfigManager {
    private final Supplier<JarvisConfig> currentSource;
    private final Supplier<ReloadResult> reloadOperation;

    public ConfigManager(Supplier<JarvisConfig> loader) {
        Objects.requireNonNull(loader, "loader");
        AtomicReference<JarvisConfig> state =
            new AtomicReference<>(
                Objects.requireNonNull(
                    loader.get(),
                    "initial config"
                )
            );
        this.currentSource = state::get;
        this.reloadOperation =
            () -> reloadStandalone(loader, state);
    }

    private ConfigManager(
        Supplier<JarvisConfig> currentSource,
        Supplier<ReloadResult> reloadOperation
    ) {
        this.currentSource = Objects.requireNonNull(
            currentSource,
            "currentSource"
        );
        this.reloadOperation = Objects.requireNonNull(
            reloadOperation,
            "reloadOperation"
        );
    }

    static ConfigManager managed(
        Supplier<JarvisConfig> currentSource,
        Supplier<ReloadResult> reloadOperation
    ) {
        return new ConfigManager(
            currentSource,
            reloadOperation
        );
    }

    public JarvisConfig current() {
        return Objects.requireNonNull(
            currentSource.get(),
            "current config"
        );
    }

    public synchronized ReloadResult reload() {
        return Objects.requireNonNull(
            reloadOperation.get(),
            "reload result"
        );
    }

    private static ReloadResult reloadStandalone(
        Supplier<JarvisConfig> loader,
        AtomicReference<JarvisConfig> state
    ) {
        JarvisConfig previous = state.get();
        try {
            JarvisConfig next = Objects.requireNonNull(
                loader.get(),
                "reloaded config"
            );
            state.set(next);
            return ReloadResult.success(
                JarvisConfigSummary.from(next)
            );
        } catch (RuntimeException failure) {
            return ReloadResult.failure(
                JarvisConfigSummary.from(previous),
                safeMessage(
                    failure,
                    "Runtime configuration reload failed."
                )
            );
        }
    }

    static String safeMessage(
        RuntimeException failure,
        String fallback
    ) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message;
    }

    public record ReloadResult(
        boolean success,
        JarvisConfigSummary activeConfig,
        String error
    ) {
        public ReloadResult {
            Objects.requireNonNull(
                activeConfig,
                "activeConfig"
            );
            if (success && error != null) {
                throw new IllegalArgumentException(
                    "Successful reload cannot have an error."
                );
            }
            if (
                !success
                    && (error == null || error.isBlank())
            ) {
                throw new IllegalArgumentException(
                    "Failed reload requires an error."
                );
            }
        }

        static ReloadResult success(
            JarvisConfigSummary activeConfig
        ) {
            return new ReloadResult(
                true,
                activeConfig,
                null
            );
        }

        static ReloadResult failure(
            JarvisConfigSummary activeConfig,
            String error
        ) {
            return new ReloadResult(
                false,
                activeConfig,
                error
            );
        }
    }
}
