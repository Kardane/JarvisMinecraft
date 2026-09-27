package io.github.kardane.jarvisminecraft.common.prompt;

import java.util.List;
import java.util.Objects;

public record PromptContentSnapshot(
    String persona,
    List<KnowledgeDocument> knowledge
) {
    public PromptContentSnapshot {
        persona = Objects.requireNonNull(persona, "persona");
        knowledge = List.copyOf(
            Objects.requireNonNull(knowledge, "knowledge")
        );
    }

    public static PromptContentSnapshot empty() {
        return new PromptContentSnapshot("", List.of());
    }
}
