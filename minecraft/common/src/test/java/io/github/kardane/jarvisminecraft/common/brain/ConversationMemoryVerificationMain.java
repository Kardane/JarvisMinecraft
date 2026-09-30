package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfigLoader;
import io.github.kardane.jarvisminecraft.common.config.PropertiesJarvisConfigSource;
import io.github.kardane.jarvisminecraft.common.logging.NoOpJarvisLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class ConversationMemoryVerificationMain {
    private static final Instant NOW =
        Instant.parse("2026-09-28T00:00:00Z");
    private static final Clock CLOCK =
        Clock.fixed(NOW, ZoneOffset.UTC);

    private ConversationMemoryVerificationMain() {
    }

    public static void main(String[] args) throws Exception {
        disabledMemoryReturnsEmpty();
        sameRequesterRelevantMemoryIsRetrieved();
        memoryIntentFallsBackToRecentTurns();
        contextBytesRemainBounded();
        System.out.println(
            "Conversation memory verification OK"
        );
    }

    private static void disabledMemoryReturnsEmpty()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-memory-disabled-"
        );
        ConfigManager config =
            new ConfigManager(() ->
                config(false, false, 4, 6, 8 * 1024)
            );
        AsyncConversationArchive archive =
            new AsyncConversationArchive(
                "main",
                root,
                config,
                CLOCK,
                NoOpJarvisLog.INSTANCE
            );

        ConversationMemorySnapshot snapshot =
            archive.retrieve(
                uuid(1),
                uuid(2),
                uuid(3),
                "전에 뭐라고 했지?"
            ).toCompletableFuture().join();

        require(
            snapshot.emptyMemory(),
            "Disabled conversation memory must return an empty snapshot."
        );
        archive.close();
    }

    private static void sameRequesterRelevantMemoryIsRetrieved()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-memory-relevant-"
        );
        ConfigManager config =
            new ConfigManager(() ->
                config(true, true, 4, 1, 8 * 1024)
            );
        AsyncConversationArchive archive =
            new AsyncConversationArchive(
                "main",
                root,
                config,
                CLOCK,
                NoOpJarvisLog.INSTANCE
            );

        UUID requester = uuid(10);
        UUID otherRequester = uuid(11);
        UUID birchSession = uuid(20);
        UUID mineSession = uuid(21);
        UUID currentSession = uuid(22);

        appendTurn(
            archive,
            requester,
            birchSession,
            uuid(30),
            "나는 건축할 때 자작나무 블록을 좋아해.",
            "자작나무 취향을 참고할게요.",
            NOW.minusSeconds(600)
        );
        appendTurn(
            archive,
            requester,
            mineSession,
            uuid(31),
            "다이아 광산은 북쪽 산 아래에 있어.",
            "북쪽 산 아래 광산으로 기억해둘게요.",
            NOW.minusSeconds(300)
        );
        appendTurn(
            archive,
            requester,
            currentSession,
            uuid(32),
            "지금은 자작나무가 싫어.",
            "현재 세션 내용입니다.",
            NOW.minusSeconds(60)
        );
        appendTurn(
            archive,
            otherRequester,
            uuid(23),
            uuid(33),
            "자작나무 관련 다른 사용자의 비밀",
            "다른 사용자 응답",
            NOW.minusSeconds(30)
        );

        archive.flushAsync()
            .toCompletableFuture()
            .join();

        ConversationMemorySnapshot snapshot =
            archive.retrieve(
                requester,
                currentSession,
                uuid(40),
                "전에 말한 자작나무 취향 기억해?"
            ).toCompletableFuture().join();

        require(
            snapshot.turnCount() == 1,
            "Relevant retrieval must respect max-turns."
        );
        require(
            snapshot.context().contains(
                "자작나무 블록을 좋아해"
            ),
            "Relevant same-requester memory was not retrieved."
        );
        require(
            !snapshot.context().contains(
                "지금은 자작나무가 싫어"
            ),
            "Current-session history must not be retrieved from archive memory."
        );
        require(
            !snapshot.context().contains(
                "다른 사용자의 비밀"
            ),
            "Conversation memory crossed requester UUID boundaries."
        );
        require(
            !snapshot.context().contains(
                "다이아 광산"
            ),
            "Lower-relevance memory displaced the requested memory."
        );

        archive.close();
    }

    private static void memoryIntentFallsBackToRecentTurns()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-memory-intent-"
        );
        ConfigManager config =
            new ConfigManager(() ->
                config(true, true, 4, 2, 8 * 1024)
            );
        AsyncConversationArchive archive =
            new AsyncConversationArchive(
                "main",
                root,
                config,
                CLOCK,
                NoOpJarvisLog.INSTANCE
            );

        UUID requester = uuid(50);
        appendTurn(
            archive,
            requester,
            uuid(51),
            uuid(52),
            "첫 번째 과거 대화",
            "첫 번째 응답",
            NOW.minusSeconds(600)
        );
        appendTurn(
            archive,
            requester,
            uuid(53),
            uuid(54),
            "최근에 에메랄드 창고 이야기를 했어.",
            "에메랄드 창고 이야기를 확인했어요.",
            NOW.minusSeconds(60)
        );
        archive.flushAsync()
            .toCompletableFuture()
            .join();

        ConversationMemorySnapshot snapshot =
            archive.retrieve(
                requester,
                uuid(55),
                uuid(56),
                "전에 내가 뭐라고 했지?"
            ).toCompletableFuture().join();

        require(
            !snapshot.emptyMemory(),
            "Explicit memory-intent query must allow recent-turn fallback."
        );
        require(
            snapshot.context().contains(
                "에메랄드 창고"
            ),
            "Memory-intent fallback did not include recent prior context."
        );
        archive.close();
    }

    private static void contextBytesRemainBounded()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-memory-bound-"
        );
        ConfigManager config =
            new ConfigManager(() ->
                config(true, true, 4, 4, 1024)
            );
        AsyncConversationArchive archive =
            new AsyncConversationArchive(
                "main",
                root,
                config,
                CLOCK,
                NoOpJarvisLog.INSTANCE
            );

        UUID requester = uuid(70);
        appendTurn(
            archive,
            requester,
            uuid(71),
            uuid(72),
            "프로젝트 " + "가".repeat(3000),
            "프로젝트 응답 " + "나".repeat(3000),
            NOW.minusSeconds(120)
        );
        archive.flushAsync()
            .toCompletableFuture()
            .join();

        ConversationMemorySnapshot snapshot =
            archive.retrieve(
                requester,
                uuid(73),
                uuid(74),
                "프로젝트 기억해?"
            ).toCompletableFuture().join();

        require(
            snapshot.utf8Bytes() <= 1024,
            "Conversation memory exceeded max-context-bytes."
        );
        archive.close();
    }

    private static void appendTurn(
        AsyncConversationArchive archive,
        UUID requester,
        UUID session,
        UUID request,
        String userText,
        String assistantText,
        Instant at
    ) {
        archive.record(
            requester,
            session,
            new ConversationEntry.UserMessage(
                userText,
                request,
                at,
                "DIRECT"
            )
        );
        archive.record(
            requester,
            session,
            new ConversationEntry.AssistantMessage(
                assistantText,
                request,
                at.plusSeconds(1),
                "FOLLOW_UP"
            )
        );
    }

    private static JarvisConfig config(
        boolean archiveEnabled,
        boolean memoryEnabled,
        int maxSourceFiles,
        int maxTurns,
        int maxContextBytes
    ) {
        Properties properties = new Properties();
        properties.setProperty(
            "jarvis.conversation-archive.enabled",
            Boolean.toString(archiveEnabled)
        );
        properties.setProperty(
            "jarvis.conversation-memory.enabled",
            Boolean.toString(memoryEnabled)
        );
        properties.setProperty(
            "jarvis.conversation-memory.lookback-days",
            "30"
        );
        properties.setProperty(
            "jarvis.conversation-memory.max-source-files",
            Integer.toString(maxSourceFiles)
        );
        properties.setProperty(
            "jarvis.conversation-memory.max-turns",
            Integer.toString(maxTurns)
        );
        properties.setProperty(
            "jarvis.conversation-memory.max-context-bytes",
            Integer.toString(maxContextBytes)
        );
        return JarvisConfigLoader.load(
            PropertiesJarvisConfigSource.from(properties)
        );
    }

    private static UUID uuid(int value) {
        return UUID.fromString(
            String.format(
                "00000000-0000-4000-8000-%012d",
                value
            )
        );
    }

    private static void require(
        boolean condition,
        String message
    ) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
