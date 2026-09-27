package io.github.kardane.jarvisminecraft.common.prompt;

import java.util.Objects;

public record KnowledgeDocument(
    String name,
    String content
) {
    public KnowledgeDocument {
        name = requireName(name);
        content = Objects.requireNonNull(content, "content");
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                "Knowledge document name must not be blank."
            );
        }
        if (
            normalized.contains("/")
                || normalized.contains("\\")
                || ".".equals(normalized)
                || "..".equals(normalized)
        ) {
            throw new IllegalArgumentException(
                "Knowledge document name must be a filename only."
            );
        }
        return normalized;
    }
}
