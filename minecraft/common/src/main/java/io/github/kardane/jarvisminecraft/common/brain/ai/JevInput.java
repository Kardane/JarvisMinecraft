package io.github.kardane.jarvisminecraft.common.brain.ai;

import io.github.kardane.jarvisminecraft.common.brain.Capability;
import io.github.kardane.jarvisminecraft.common.brain.ConversationEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record JevInput(
    String latestMessage,
    String shortTopic,
    List<String> capabilities,
    String interactionOrigin
) {
    public JevInput(
        String latestMessage,
        String shortTopic,
        List<String> capabilities
    ) {
        this(
            latestMessage,
            shortTopic,
            capabilities,
            "DIRECT"
        );
    }

    public JevInput {
        latestMessage = Objects.requireNonNull(
            latestMessage,
            "latestMessage"
        );
        shortTopic = Objects.requireNonNull(
            shortTopic,
            "shortTopic"
        );
        capabilities = List.copyOf(
            Objects.requireNonNull(
                capabilities,
                "capabilities"
            )
        );
        interactionOrigin = Objects.requireNonNull(
            interactionOrigin,
            "interactionOrigin"
        );
        if (interactionOrigin.isBlank()) {
            throw new IllegalArgumentException(
                "interactionOrigin must not be blank."
            );
        }
    }

    public static JevInput fromConversation(
        List<ConversationEntry> history,
        List<Capability> capabilities
    ) {
        return fromConversation(
            history,
            capabilities,
            "DIRECT"
        );
    }

    public static JevInput fromConversation(
        List<ConversationEntry> history,
        List<Capability> capabilities,
        String interactionOrigin
    ) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(capabilities, "capabilities");

        String latestMessage = "";
        for (int index = history.size() - 1; index >= 0; index -= 1) {
            ConversationEntry entry = history.get(index);
            if (
                entry
                    instanceof ConversationEntry.UserMessage user
            ) {
                latestMessage = user.text();
                break;
            }
        }

        List<String> topicEntries = new ArrayList<>();
        for (ConversationEntry entry : history) {
            if (
                entry
                    instanceof ConversationEntry.UserMessage user
            ) {
                topicEntries.add(
                    "user: " + clip(user.text(), 320)
                );
            } else if (
                entry
                    instanceof ConversationEntry.AssistantMessage assistant
            ) {
                topicEntries.add(
                    "assistant: "
                        + clip(assistant.text(), 320)
                );
            }
        }

        int from = Math.max(0, topicEntries.size() - 6);
        String shortTopic = String.join(
            "\n",
            topicEntries.subList(from, topicEntries.size())
        );

        return new JevInput(
            latestMessage,
            shortTopic,
            capabilities.stream()
                .map(Capability::name)
                .toList(),
            interactionOrigin
        );
    }

    private static String clip(
        String value,
        int maxLength
    ) {
        return value.length() <= maxLength
            ? value
            : value.substring(0, maxLength);
    }
}
