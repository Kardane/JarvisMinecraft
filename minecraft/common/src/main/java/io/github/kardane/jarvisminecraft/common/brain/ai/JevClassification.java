package io.github.kardane.jarvisminecraft.common.brain.ai;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record JevClassification(
    JevCategory category,
    double confidence,
    Map<JevCategory, Double> probabilities,
    JevEngagement engagement,
    double engagementConfidence,
    ReasoningLevel reasoning,
    double reasoningConfidence,
    String model,
    String requestId
) {
    public JevClassification(
        JevCategory category,
        double confidence,
        Map<JevCategory, Double> probabilities,
        String model,
        String requestId
    ) {
        this(
            category,
            confidence,
            probabilities,
            JevEngagement.RESPOND,
            1.0,
            ReasoningLevel.MEDIUM,
            1.0,
            model,
            requestId
        );
    }

    public JevClassification {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(engagement, "engagement");
        Objects.requireNonNull(reasoning, "reasoning");
        Objects.requireNonNull(model, "model");
        requireProbability(confidence, "Jev route confidence");
        requireProbability(
            engagementConfidence,
            "Jev engagement confidence"
        );
        requireProbability(
            reasoningConfidence,
            "Jev reasoning confidence"
        );

        EnumMap<JevCategory, Double> copy =
            new EnumMap<>(JevCategory.class);
        for (
            Map.Entry<JevCategory, Double> entry
                : Objects.requireNonNull(
                    probabilities,
                    "probabilities"
                ).entrySet()
        ) {
            Double value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException(
                    "Jev probability must not be null."
                );
            }
            requireProbability(value, "Jev route probability");
            copy.put(
                Objects.requireNonNull(
                    entry.getKey(),
                    "probability key"
                ),
                value
            );
        }
        probabilities = Map.copyOf(copy);
    }

    private static void requireProbability(
        double value,
        String field
    ) {
        if (
            !Double.isFinite(value)
                || value < 0.0
                || value > 1.0
        ) {
            throw new IllegalArgumentException(
                field + " must be between 0 and 1."
            );
        }
    }
}
