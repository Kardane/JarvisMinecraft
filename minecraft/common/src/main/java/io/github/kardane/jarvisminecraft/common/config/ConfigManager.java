package io.github.kardane.jarvisminecraft.common.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class ConfigManager {
    private final Supplier<JarvisConfig> loader;
    private final AtomicReference<JarvisConfig> current;

    public ConfigManager(Supplier<JarvisConfig> loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.current = new AtomicReference<>(
            Objects.requireNonNull(loader.get(), "initial config")
        );
    }

    public JarvisConfig current() {
        return current.get();
    }

    public synchronized ReloadResult reload() {
        JarvisConfig previous = current.get();
        try {
            JarvisConfig next = Objects.requireNonNull(
                loader.get(),
                "reloaded config"
            );
            current.set(next);
            return ReloadResult.success(JarvisConfigSummary.from(next));
        } catch (RuntimeException failure) {
            return ReloadResult.failure(
                JarvisConfigSummary.from(previous),
                safeMessage(failure)
            );
        }
    }

    private String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "Runtime configuration reload failed.";
        }
        return message;
    }

    public record ReloadResult(
        boolean success,
        JarvisConfigSummary activeConfig,
        String error
    ) {
        public ReloadResult {
            Objects.requireNonNull(activeConfig, "activeConfig");
            if (success && error != null) {
                throw new IllegalArgumentException(
                    "Successful reload cannot have an error."
                );
            }
            if (!success && (error == null || error.isBlank())) {
                throw new IllegalArgumentException(
                    "Failed reload requires an error."
                );
            }
        }

        static ReloadResult success(JarvisConfigSummary activeConfig) {
            return new ReloadResult(true, activeConfig, null);
        }

        static ReloadResult failure(
            JarvisConfigSummary activeConfig,
            String error
        ) {
            return new ReloadResult(false, activeConfig, error);
        }
    }
}
