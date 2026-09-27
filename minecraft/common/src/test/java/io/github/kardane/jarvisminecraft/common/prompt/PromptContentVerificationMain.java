package io.github.kardane.jarvisminecraft.common.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PromptContentVerificationMain {
    private PromptContentVerificationMain() {
    }

    public static void main(String[] args) throws Exception {
        missingContentIsValid();
        validContentLoadsDeterministically();
        limitsRejectInvalidContent();
        invalidUtf8IsRejected();
        symlinkEscapeIsRejectedWhenSupported();
        templatesAreCreatedWithoutOverwrite();
        System.out.println("Prompt content verification OK");
    }

    private static void missingContentIsValid() throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-prompt-missing-"
        );
        PromptContentSnapshot snapshot =
            new PromptContentLoader(root).load();

        require(
            snapshot.persona().isEmpty(),
            "Missing persona must produce empty content."
        );
        require(
            snapshot.knowledge().isEmpty(),
            "Missing knowledge directory must produce empty content."
        );
    }

    private static void validContentLoadsDeterministically()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-prompt-valid-"
        );
        Files.writeString(
            root.resolve("persona.md"),
            "PERSONA",
            StandardCharsets.UTF_8
        );
        Path knowledge = Files.createDirectories(
            root.resolve("knowledge")
        );
        Files.writeString(
            knowledge.resolve("B.md"),
            "B",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            knowledge.resolve("a.md"),
            "A",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            knowledge.resolve("notes.txt"),
            "ignored",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            knowledge.resolve(
                PromptContentTemplates.KNOWLEDGE_README
            ),
            "operator guidance only",
            StandardCharsets.UTF_8
        );
        Files.createDirectories(
            knowledge.resolve("nested")
        );
        Files.writeString(
            knowledge.resolve("nested").resolve("nested.md"),
            "nested",
            StandardCharsets.UTF_8
        );

        PromptContentSnapshot snapshot =
            new PromptContentLoader(root).load();

        require(
            "PERSONA".equals(snapshot.persona()),
            "Valid UTF-8 persona did not load."
        );
        require(
            snapshot.knowledge().stream()
                .map(KnowledgeDocument::name)
                .toList()
                .equals(List.of("a.md", "B.md")),
            "Knowledge files were not sorted deterministically or ignored correctly."
        );
    }

    private static void limitsRejectInvalidContent()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-prompt-limits-"
        );
        Path knowledge = Files.createDirectories(
            root.resolve("knowledge")
        );
        Files.writeString(
            knowledge.resolve("a.md"),
            "12345",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            knowledge.resolve("b.md"),
            "67890",
            StandardCharsets.UTF_8
        );

        expectRejected(
            () -> new PromptContentLoader(
                root,
                new PromptContentLoader.Limits(
                    32,
                    1,
                    32,
                    64
                )
            ).load(),
            "Knowledge file-count limit must reject excess files."
        );

        expectRejected(
            () -> new PromptContentLoader(
                root,
                new PromptContentLoader.Limits(
                    32,
                    8,
                    4,
                    64
                )
            ).load(),
            "Knowledge per-file byte limit must reject oversized content."
        );

        expectRejected(
            () -> new PromptContentLoader(
                root,
                new PromptContentLoader.Limits(
                    32,
                    8,
                    8,
                    8
                )
            ).load(),
            "Knowledge aggregate byte limit must reject oversized content."
        );

        Files.writeString(
            root.resolve("persona.md"),
            "12345",
            StandardCharsets.UTF_8
        );
        expectRejected(
            () -> new PromptContentLoader(
                root,
                new PromptContentLoader.Limits(
                    4,
                    8,
                    8,
                    64
                )
            ).load(),
            "Persona byte limit must reject oversized content."
        );
    }

    private static void invalidUtf8IsRejected()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-prompt-utf8-"
        );
        Files.write(
            root.resolve("persona.md"),
            new byte[] {
                (byte) 0xC3,
                (byte) 0x28
            }
        );

        expectRejected(
            () -> new PromptContentLoader(root).load(),
            "Malformed UTF-8 must be rejected."
        );
    }

    private static void symlinkEscapeIsRejectedWhenSupported()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-prompt-link-root-"
        );
        Path outside = Files.createTempDirectory(
            "jarvis-prompt-link-outside-"
        );
        Files.writeString(
            outside.resolve("outside.md"),
            "outside",
            StandardCharsets.UTF_8
        );
        Path knowledge = Files.createDirectories(
            root.resolve("knowledge")
        );
        Path link = knowledge.resolve("escape.md");

        try {
            Files.createSymbolicLink(
                link,
                outside.resolve("outside.md")
            );
        } catch (
            UnsupportedOperationException
                | IOException
                | SecurityException unsupported
        ) {
            return;
        }

        expectRejected(
            () -> new PromptContentLoader(root).load(),
            "Knowledge symlink escape must be rejected."
        );
    }

    private static void templatesAreCreatedWithoutOverwrite()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-prompt-template-"
        );

        PromptContentTemplates.ensureDefaults(root);

        Path persona = root.resolve("persona.md");
        Path readme = root
            .resolve("knowledge")
            .resolve(PromptContentTemplates.KNOWLEDGE_README);

        require(
            Files.isRegularFile(persona),
            "Default persona template was not created."
        );
        require(
            Files.isRegularFile(readme),
            "Knowledge README template was not created."
        );

        Files.writeString(
            persona,
            "CUSTOM PERSONA",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            readme,
            "CUSTOM README",
            StandardCharsets.UTF_8
        );

        PromptContentTemplates.ensureDefaults(root);

        require(
            "CUSTOM PERSONA".equals(
                Files.readString(
                    persona,
                    StandardCharsets.UTF_8
                )
            ),
            "Template creation overwrote operator persona content."
        );
        require(
            "CUSTOM README".equals(
                Files.readString(
                    readme,
                    StandardCharsets.UTF_8
                )
            ),
            "Template creation overwrote operator knowledge guidance."
        );

        PromptContentSnapshot loaded =
            new PromptContentLoader(root).load();
        require(
            loaded.knowledge().isEmpty(),
            "knowledge/README.md must never be injected into model context."
        );
    }

    private static void expectRejected(
        ThrowingRunnable action,
        String message
    ) {
        boolean rejected = false;
        try {
            action.run();
        } catch (RuntimeException expected) {
            rejected = true;
        } catch (Exception failure) {
            throw new AssertionError(
                "Unexpected checked exception.",
                failure
            );
        }
        require(rejected, message);
    }

    private static void require(
        boolean condition,
        String message
    ) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
