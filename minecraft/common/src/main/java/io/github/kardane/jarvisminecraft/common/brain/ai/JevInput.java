package io.github.kardane.jarvisminecraft.common.brain.ai;

import java.util.List;
import java.util.Objects;

public record JevInput(
    String latestMessage,
    String shortTopic,
    List<String> capabilities
) {
    public JevInput {
        latestMessage = Objects.requireNonNull(latestMessage, "latestMessage");
        shortTopic = Objects.requireNonNull(shortTopic, "shortTopic");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    }
}
