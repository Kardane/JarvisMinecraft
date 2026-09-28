package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfigLoader;
import io.github.kardane.jarvisminecraft.common.config.PropertiesJarvisConfigSource;
import io.github.kardane.jarvisminecraft.common.logging.NoOpJarvisLog;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.NoArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class ConversationArchiveVerificationMain {
    private static final Instant NOW =
        Instant.parse("2026-09-28T00:00:00Z");
    private static final Clock CLOCK =
        Clock.fixed(NOW, ZoneOffset.UTC);

    private ConversationArchiveVerificationMain() {
    }

    public static void main(String[] args) throws Exception {
        disabledArchiveDoesNotWrite();
        directConversationIsArchived();
        rotationAndRetentionAreBounded();
        System.out.println(
            "Conversation archive verification OK"
        );
    }

    private static void disabledArchiveDoesNotWrite()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-conversation-disabled-"
        );
        ConfigManager config =
            new ConfigManager(JarvisConfig::defaults);

        AsyncConversationArchive archive =
            new AsyncConversationArchive(
                "main",
                root,
                config,
                CLOCK,
                NoOpJarvisLog.INSTANCE
            );

        archive.record(
            uuid(1),
            uuid(2),
            new ConversationEntry.UserMessage(
                "not persisted",
                uuid(3),
                NOW,
                "DIRECT"
            )
        );
        archive.flushAsync()
            .toCompletableFuture()
            .join();
        archive.close();

        require(
            !Files.exists(
                root.resolve(
                    AsyncConversationArchive.DIRECTORY_NAME
                )
            ),
            "Disabled conversation archive must not create files."
        );
    }

    private static void directConversationIsArchived()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-conversation-enabled-"
        );
        ConfigManager config =
            new ConfigManager(() ->
                archiveConfig(65_536, 4)
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
        UUID session = uuid(11);
        archive.record(
            requester,
            session,
            new ConversationEntry.UserMessage(
                "hello jarvis",
                uuid(12),
                NOW,
                "DIRECT"
            )
        );
        archive.record(
            requester,
            session,
            new ConversationEntry.AssistantMessage(
                "hello operator",
                uuid(12),
                NOW.plusSeconds(1),
                "FOLLOW_UP"
            )
        );
        archive.record(
            requester,
            session,
            new ConversationEntry.UserMessage(
                "ambient public context",
                uuid(13),
                NOW.plusSeconds(2),
                "PROACTIVE"
            )
        );
        archive.record(
            requester,
            session,
            new ConversationEntry.AssistantMessage(
                "proactive response",
                uuid(13),
                NOW.plusSeconds(3),
                "PROACTIVE"
            )
        );
        archive.record(
            requester,
            session,
            new ConversationEntry.ToolMessage(
                ToolName.GET_SERVER_STATUS,
                uuid(14),
                ToolResult.error(
                    ErrorCode.NOT_FOUND,
                    "tool fixture must not persist",
                    false,
                    NOW,
                    "Fixture"
                ),
                uuid(12),
                NOW.plusSeconds(4)
            )
        );

        archive.flushAsync()
            .toCompletableFuture()
            .join();
        archive.close();

        Path directory = root.resolve(
            AsyncConversationArchive.DIRECTORY_NAME
        );
        require(
            Files.isRegularFile(
                directory.resolve(
                    AsyncConversationArchive.README_FILE
                )
            ),
            "Conversation archive README was not created."
        );

        List<Path> files = jsonlFiles(directory);
        require(
            files.size() == 1,
            "Expected one JSONL archive file."
        );

        String stored = Files.readString(
            files.getFirst(),
            StandardCharsets.UTF_8
        );
        require(
            stored.contains("hello jarvis")
                && stored.contains("hello operator"),
            "Direct/follow-up messages were not archived."
        );
        require(
            stored.contains("\"role\":\"USER\"")
                && stored.contains(
                    "\"role\":\"ASSISTANT\""
                ),
            "Archive role metadata is missing."
        );
        require(
            stored.contains("\"origin\":\"DIRECT\"")
                && stored.contains(
                    "\"origin\":\"FOLLOW_UP\""
                ),
            "Archive origin metadata is missing."
        );
        require(
            !stored.contains("ambient public context")
                && !stored.contains("proactive response"),
            "Proactive ambient conversation must not be archived."
        );
        require(
            !stored.contains("tool fixture must not persist")
                && !stored.contains("get_server_status"),
            "Tool results must not be archived."
        );
    }

    private static void rotationAndRetentionAreBounded()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-conversation-rotation-"
        );
        ConfigManager config =
            new ConfigManager(() ->
                archiveConfig(65_536, 2)
            );
        AsyncConversationArchive archive =
            new AsyncConversationArchive(
                "main",
                root,
                config,
                CLOCK,
                NoOpJarvisLog.INSTANCE
            );

        UUID requester = uuid(20);
        UUID session = uuid(21);
        String payload = "x".repeat(40_000);

        for (int index = 0; index < 6; index += 1) {
            archive.record(
                requester,
                session,
                new ConversationEntry.UserMessage(
                    payload + index,
                    UUID.randomUUID(),
                    NOW.plusSeconds(index),
                    "DIRECT"
                )
            );
        }

        archive.flushAsync()
            .toCompletableFuture()
            .join();
        archive.close();

        List<Path> files = jsonlFiles(
            root.resolve(
                AsyncConversationArchive.DIRECTORY_NAME
            )
        );
        require(
            files.size() <= 2,
            "Conversation archive exceeded max-files."
        );
        for (Path file : files) {
            require(
                Files.size(file) <= 65_536,
                "Conversation archive exceeded max-file-bytes."
            );
        }
    }

    private static JarvisConfig archiveConfig(
        int maxFileBytes,
        int maxFiles
    ) {
        Properties properties = new Properties();
        properties.setProperty(
            "jarvis.conversation-archive.enabled",
            "true"
        );
        properties.setProperty(
            "jarvis.conversation-archive.max-file-bytes",
            Integer.toString(maxFileBytes)
        );
        properties.setProperty(
            "jarvis.conversation-archive.max-files",
            Integer.toString(maxFiles)
        );
        return JarvisConfigLoader.load(
            PropertiesJarvisConfigSource.from(properties)
        );
    }

    private static List<Path> jsonlFiles(
        Path directory
    ) throws Exception {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                .filter(path ->
                    path.getFileName()
                        .toString()
                        .endsWith(".jsonl")
                )
                .toList();
        }
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
