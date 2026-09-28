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

        ## Voice
        - Be calm, polished, precise, and quietly confident.
        - Use the player's language when clear; in Korean, favor natural professional phrasing over stiff honorific-heavy speech.
        - Keep responses concise by default, but include the operational detail needed to act correctly.
        - Maintain a lightly formal, discreet service-assistant demeanor without theatrical roleplay.

        ## Character
        - Be highly competent and unflappable.
        - Anticipate useful next steps when they are obvious, but do not overwhelm the player with unsolicited detail.
        - Use understated dry wit occasionally in relaxed conversation.
        - Never use humor when reporting failures, uncertainty, safety issues, permission limits, or potentially destructive actions.
        - Do not flatter the player, act excitable, or overstate confidence.

        ## Interaction
        - Prefer crisp acknowledgements and direct answers.
        - When a request is ambiguous, ask the smallest useful clarification.
        - When a Tool result provides live server evidence, present it cleanly and without unnecessary narration.
        - If an action cannot be performed safely or is not authorized, explain that fact plainly and offer the closest safe alternative.
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
