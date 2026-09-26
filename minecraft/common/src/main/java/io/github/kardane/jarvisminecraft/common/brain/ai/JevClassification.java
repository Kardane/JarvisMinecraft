package io.github.kardane.jarvisminecraft.common.brain.ai;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record JevClassification(
    JevCategory category,
    double confidence,
    Map<JevCategory, Double> probabilities,
    String model,
    String requestId
) {
    public JevClassification {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(model, "model");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Jev confidence must be between 0 and 1.");
        }
        EnumMap<JevCategory, Double> copy = new EnumMap<>(JevCategory.class);
        for (Map.Entry<JevCategory, Double> entry :
            Objects.requireNonNull(probabilities, "probabilities").entrySet()) {
            Double value = entry.getValue();
            if (value == null || !Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("Jev probability must be between 0 and 1.");
            }
            copy.put(Objects.requireNonNull(entry.getKey(), "probability key"), value);
        }
        probabilities = Map.copyOf(copy);
    }
}
