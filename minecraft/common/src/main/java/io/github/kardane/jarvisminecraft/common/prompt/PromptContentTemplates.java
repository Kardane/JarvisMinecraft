package io.github.kardane.jarvisminecraft.common.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class PromptContentTemplates {
    public static final String KNOWLEDGE_README = "README.md";

    private static final String DEFAULT_PERSONA = """
        # JARVIS Personality

        - Be concise and practical.
        - Use the player's language when clear.
        - Be polite without excessive formality.
        - Prefer direct answers over roleplay.
        """;

    private static final String DEFAULT_KNOWLEDGE_README = """
        # JARVIS Server Knowledge

        Add Markdown files to this directory for server-specific reference material.

        Good examples:
        - server rules
        - named locations
        - ranks
        - lore
        - common commands or services

        This README is operator guidance and is not loaded into JARVIS context.

        Do not place secrets, API keys, passwords, private player data, or
        instructions intended to override JARVIS safety or Tool policy here.
        """;

    private PromptContentTemplates() {
    }

    public static void ensureDefaults(Path configRoot) {
        Path root = Objects.requireNonNull(
            configRoot,
            "configRoot"
        ).toAbsolutePath().normalize();
        Path knowledge =
            root.resolve(PromptContentLoader.KNOWLEDGE_DIRECTORY);

        try {
            Files.createDirectories(root);
            Files.createDirectories(knowledge);
            writeIfMissing(
                root.resolve(PromptContentLoader.PERSONA_FILE),
                DEFAULT_PERSONA
            );
            writeIfMissing(
                knowledge.resolve(KNOWLEDGE_README),
                DEFAULT_KNOWLEDGE_README
            );
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Could not create JARVIS prompt-content templates.",
                failure
            );
        }
    }

    private static void writeIfMissing(
        Path path,
        String content
    ) throws IOException {
        try {
            Files.writeString(
                path,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
        } catch (FileAlreadyExistsException ignored) {
            // Never overwrite operator-edited content.
        }
    }
}
