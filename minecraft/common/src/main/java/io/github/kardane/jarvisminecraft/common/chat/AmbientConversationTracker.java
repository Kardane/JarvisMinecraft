package io.github.kardane.jarvisminecraft.common.chat;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AmbientConversationTracker {
    private static final int MAX_RETAINED = 50;
    private final Deque<AmbientChatMessage> messages =
        new ArrayDeque<>();

    public synchronized void record(
        UUID senderUuid,
        String senderName,
        String text,
        Instant observedAt
    ) {
        messages.addLast(
            new AmbientChatMessage(
                senderUuid,
                senderName,
                clip(text, 512),
                observedAt
            )
        );
        while (messages.size() > MAX_RETAINED) {
            messages.removeFirst();
        }
    }

    public synchronized List<AmbientChatMessage> snapshot(
        int maxMessages
    ) {
        if (maxMessages < 1) {
            throw new IllegalArgumentException(
                "maxMessages must be positive."
            );
        }
        List<AmbientChatMessage> all =
            new ArrayList<>(messages);
        int from = Math.max(
            0,
            all.size() - maxMessages
        );
        return List.copyOf(
            all.subList(from, all.size())
        );
    }

    public synchronized void clearActor(UUID senderUuid) {
        Objects.requireNonNull(senderUuid, "senderUuid");
        messages.removeIf(
            message -> message.senderUuid().equals(
                senderUuid
            )
        );
    }

    public static String promptContext(
        List<AmbientChatMessage> context
    ) {
        Objects.requireNonNull(context, "context");
        StringBuilder output = new StringBuilder(
            "다음은 공개 채팅 흐름입니다. JARVIS가 먼저 대화에 참여하기로 이미 결정된 상황입니다. "
                + "가장 최근 메시지를 중심으로 자연스럽고 간결하게 답하세요.\n"
        );
        for (AmbientChatMessage message : context) {
            output.append(message.senderName())
                .append(": ")
                .append(clip(message.text(), 320))
                .append('\n');
        }
        return output.toString().trim();
    }

    private static String clip(String value, int maxLength) {
        return value.length() <= maxLength
            ? value
            : value.substring(0, maxLength);
    }
}
