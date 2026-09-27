package io.github.kardane.jarvisminecraft.common.brain.ai;

import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;

import java.util.Objects;

public final class ReasoningPolicy {
    private final ConfigManager configManager;

    public ReasoningPolicy(ConfigManager configManager) {
        this.configManager = Objects.requireNonNull(
            configManager,
            "configManager"
        );
    }

    public static ReasoningPolicy defaults() {
        return new ReasoningPolicy(
            new ConfigManager(JarvisConfig::defaults)
        );
    }

    public ReasoningLevel resolve(JevClassification classification) {
        Objects.requireNonNull(classification, "classification");
        JarvisConfig.Reasoning config =
            configManager.current().model().reasoning();

        if (config.mode() != JarvisConfig.ReasoningMode.AUTO) {
            return concrete(config.mode());
        }
        return classification.reasoning();
    }

    public ReasoningLevel fallback() {
        JarvisConfig.Reasoning config =
            configManager.current().model().reasoning();

        if (config.mode() != JarvisConfig.ReasoningMode.AUTO) {
            return concrete(config.mode());
        }
        return concrete(config.fallback());
    }

    private ReasoningLevel concrete(JarvisConfig.ReasoningMode mode) {
        return switch (mode) {
            case NONE -> ReasoningLevel.NONE;
            case LOW -> ReasoningLevel.LOW;
            case MEDIUM -> ReasoningLevel.MEDIUM;
            case HIGH -> ReasoningLevel.HIGH;
            case AUTO -> throw new IllegalArgumentException(
                "AUTO is not a concrete reasoning level."
            );
        };
    }
}
