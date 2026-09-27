package io.github.kardane.jarvisminecraft.common.config;

import io.github.kardane.jarvisminecraft.common.prompt.PromptContentLoader;
import io.github.kardane.jarvisminecraft.common.prompt.PromptContentManager;
import io.github.kardane.jarvisminecraft.common.prompt.PromptContentSnapshot;
import io.github.kardane.jarvisminecraft.common.prompt.PromptContentTemplates;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class RuntimeConfigurationManager {
    private final Path configRoot;
    private final Supplier<JarvisConfig> configLoader;
    private final AtomicReference<RuntimeSnapshot> current;
    private final ConfigManager configManager;
    private final PromptContentManager promptContentManager;

    public RuntimeConfigurationManager(
        Path configRoot,
        Supplier<JarvisConfig> configLoader
    ) {
        this.configRoot = Objects.requireNonNull(
            configRoot,
            "configRoot"
        ).toAbsolutePath().normalize();
        this.configLoader = Objects.requireNonNull(
            configLoader,
            "configLoader"
        );

        PromptContentTemplates.ensureDefaults(
            this.configRoot
        );

        JarvisConfig initialConfig =
            Objects.requireNonNull(
                configLoader.get(),
                "initial config"
            );
        PromptContentSnapshot initialPrompt =
            loadPromptContent(initialConfig);

        this.current = new AtomicReference<>(
            new RuntimeSnapshot(
                initialConfig,
                initialPrompt
            )
        );
        this.configManager = ConfigManager.managed(
            () -> current.get().config(),
            this::reloadConfigView
        );
        this.promptContentManager =
            PromptContentManager.managed(
                () -> current.get().promptContent(),
                this::reloadPromptView
            );
    }

    public RuntimeSnapshot current() {
        return current.get();
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public PromptContentManager promptContentManager() {
        return promptContentManager;
    }

    public synchronized ReloadResult reload() {
        RuntimeSnapshot previous = current.get();
        try {
            JarvisConfig nextConfig =
                Objects.requireNonNull(
                    configLoader.get(),
                    "reloaded config"
                );
            PromptContentSnapshot nextPrompt =
                loadPromptContent(nextConfig);

            RuntimeSnapshot next = new RuntimeSnapshot(
                nextConfig,
                nextPrompt
            );
            current.set(next);

            return ReloadResult.success(next);
        } catch (RuntimeException failure) {
            return ReloadResult.failure(
                previous,
                ConfigManager.safeMessage(
                    failure,
                    "Runtime configuration reload failed."
                )
            );
        }
    }

    private PromptContentSnapshot loadPromptContent(
        JarvisConfig config
    ) {
        JarvisConfig.Knowledge knowledge =
            config.knowledge();
        PromptContentLoader.Limits limits =
            new PromptContentLoader.Limits(
                PromptContentLoader.Limits
                    .DEFAULT_PERSONA_MAX_BYTES,
                knowledge.maxFiles(),
                knowledge.maxFileBytes(),
                knowledge.maxTotalBytes()
            );

        return new PromptContentLoader(
            configRoot,
            config.personality().enabled(),
            knowledge.enabled(),
            limits
        ).load();
    }

    private ConfigManager.ReloadResult reloadConfigView() {
        ReloadResult result = reload();
        if (result.success()) {
            return ConfigManager.ReloadResult.success(
                result.activeConfig()
            );
        }
        return ConfigManager.ReloadResult.failure(
            result.activeConfig(),
            result.error()
        );
    }

    private PromptContentManager.ReloadResult
        reloadPromptView() {
        ReloadResult result = reload();
        if (result.success()) {
            return PromptContentManager.ReloadResult.success(
                result.activeContent()
            );
        }
        return PromptContentManager.ReloadResult.failure(
            result.activeContent(),
            result.error()
        );
    }

    public record RuntimeSnapshot(
        JarvisConfig config,
        PromptContentSnapshot promptContent
    ) {
        public RuntimeSnapshot {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(
                promptContent,
                "promptContent"
            );
        }
    }

    public record ReloadResult(
        boolean success,
        JarvisConfigSummary activeConfig,
        PromptContentManager.ContentSummary activeContent,
        String error
    ) {
        public ReloadResult {
            Objects.requireNonNull(
                activeConfig,
                "activeConfig"
            );
            Objects.requireNonNull(
                activeContent,
                "activeContent"
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
            RuntimeSnapshot snapshot
        ) {
            return new ReloadResult(
                true,
                JarvisConfigSummary.from(
                    snapshot.config()
                ),
                PromptContentManager.ContentSummary.from(
                    snapshot.promptContent()
                ),
                null
            );
        }

        static ReloadResult failure(
            RuntimeSnapshot snapshot,
            String error
        ) {
            return new ReloadResult(
                false,
                JarvisConfigSummary.from(
                    snapshot.config()
                ),
                PromptContentManager.ContentSummary.from(
                    snapshot.promptContent()
                ),
                error
            );
        }
    }
}
