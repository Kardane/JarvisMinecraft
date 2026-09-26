package io.github.kardane.jarvisminecraft.common.brain.ai;

import io.github.kardane.jarvisminecraft.common.brain.ConversationEntry;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolMessage;

import java.util.ArrayList;
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

    public static JevInput fromConversation(
        List<ConversationEntry> history,
        List<ProtocolMessage.Capability> capabilities
    ) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(capabilities, "capabilities");

        String latestMessage = "";
        for (int index = history.size() - 1; index >= 0; index -= 1) {
            ConversationEntry entry = history.get(index);
            if (entry instanceof ConversationEntry.UserMessage user) {
                latestMessage = user.text();
                break;
            }
        }

        List<String> topicEntries = new ArrayList<>();
        for (ConversationEntry entry : history) {
            if (entry instanceof ConversationEntry.UserMessage user) {
                topicEntries.add("user: " + clip(user.text(), 320));
            } else if (entry instanceof ConversationEntry.AssistantMessage assistant) {
                topicEntries.add("assistant: " + clip(assistant.text(), 320));
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
            capabilities.stream().map(ProtocolMessage.Capability::name).toList()
        );
    }

    private static String clip(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
