package io.github.kardane.jarvisminecraft.common.config;

import java.util.Objects;

public record JarvisConfigSummary(
    String model,
    JarvisConfig.InteractionMode interactionMode,
    JarvisConfig.AudienceMode audienceMode,
    JarvisConfig.ReasoningMode reasoningMode,
    JarvisConfig.ExecutionMode executionMode,
    JarvisConfig.ExecutionActors executionActors,
    boolean schedulingEnabled,
    boolean conversationArchiveEnabled,
    int wakeWordCount
) {
    public JarvisConfigSummary {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(interactionMode, "interactionMode");
        Objects.requireNonNull(audienceMode, "audienceMode");
        Objects.requireNonNull(reasoningMode, "reasoningMode");
        Objects.requireNonNull(executionMode, "executionMode");
        Objects.requireNonNull(executionActors, "executionActors");
        if (wakeWordCount < 0) {
            throw new IllegalArgumentException("wakeWordCount");
        }
    }

    public static JarvisConfigSummary from(JarvisConfig config) {
        Objects.requireNonNull(config, "config");
        return new JarvisConfigSummary(
            config.model().name(),
            config.interaction().mode(),
            config.interaction().audience().mode(),
            config.model().reasoning().mode(),
            config.execution().mode(),
            config.execution().actors(),
            config.scheduling().enabled(),
            config.conversationArchive().enabled(),
            config.interaction().wakeWords().size()
        );
    }

    public String toLogLine() {
        return "model="
            + model
            + ", interaction="
            + interactionMode
            + ", audience="
            + audienceMode
            + ", reasoning="
            + reasoningMode
            + ", execution="
            + executionMode
            + ", executionActors="
            + executionActors
            + ", scheduling="
            + (schedulingEnabled ? "ENABLED" : "DISABLED")
            + ", conversationArchive="
            + (conversationArchiveEnabled ? "ENABLED" : "DISABLED")
            + ", wakeWords="
            + wakeWordCount;
    }
}
