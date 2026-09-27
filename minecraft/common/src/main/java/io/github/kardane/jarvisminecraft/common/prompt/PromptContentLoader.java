package io.github.kardane.jarvisminecraft.common.prompt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

public final class PromptContentLoader {
    public static final String PERSONA_FILE = "persona.md";
    public static final String KNOWLEDGE_DIRECTORY = "knowledge";

    private static final int READ_BUFFER_BYTES = 8 * 1024;

    private final Path configRoot;
    private final Limits limits;

    public PromptContentLoader(Path configRoot) {
        this(configRoot, Limits.defaults());
    }

    public PromptContentLoader(
        Path configRoot,
        Limits limits
    ) {
        this.configRoot = Objects.requireNonNull(
            configRoot,
            "configRoot"
        ).toAbsolutePath().normalize();
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public PromptContentSnapshot load() {
        if (!Files.exists(configRoot, LinkOption.NOFOLLOW_LINKS)) {
            return PromptContentSnapshot.empty();
        }
        if (!Files.isDirectory(configRoot)) {
            throw new IllegalArgumentException(
                "JARVIS configuration root must be a directory."
            );
        }

        Path realRoot = realPath(
            configRoot,
            "Could not resolve the JARVIS configuration root."
        );
        String persona = loadPersona(realRoot);
        List<KnowledgeDocument> knowledge = loadKnowledge(realRoot);
        return new PromptContentSnapshot(persona, knowledge);
    }

    private String loadPersona(Path realRoot) {
        Path persona = configRoot.resolve(PERSONA_FILE);
        if (!Files.exists(persona, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }

        Path realPersona = requireInsideRoot(
            persona,
            realRoot,
            "persona.md"
        );
        if (!Files.isRegularFile(realPersona)) {
            throw new IllegalArgumentException(
                "persona.md must be a regular file."
            );
        }
        if (!Files.isReadable(realPersona)) {
            throw new IllegalArgumentException(
                "persona.md is not readable."
            );
        }

        return readUtf8(
            realPersona,
            limits.personaMaxBytes(),
            "persona.md"
        ).content();
    }

    private List<KnowledgeDocument> loadKnowledge(
        Path realRoot
    ) {
        Path knowledgeDirectory =
            configRoot.resolve(KNOWLEDGE_DIRECTORY);
        if (
            !Files.exists(
                knowledgeDirectory,
                LinkOption.NOFOLLOW_LINKS
            )
        ) {
            return List.of();
        }

        Path realKnowledgeDirectory = requireInsideRoot(
            knowledgeDirectory,
            realRoot,
            "knowledge directory"
        );
        if (!Files.isDirectory(realKnowledgeDirectory)) {
            throw new IllegalArgumentException(
                "knowledge must be a directory."
            );
        }
        if (!Files.isReadable(realKnowledgeDirectory)) {
            throw new IllegalArgumentException(
                "knowledge directory is not readable."
            );
        }

        List<Path> markdownFiles;
        try (Stream<Path> entries = Files.list(knowledgeDirectory)) {
            markdownFiles = entries
                .filter(this::isMarkdownEntry)
                .sorted(
                    Comparator
                        .comparing(this::normalizedFilename)
                        .thenComparing(
                            path -> path.getFileName().toString()
                        )
                )
                .toList();
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Could not list knowledge files.",
                failure
            );
        }

        if (markdownFiles.size() > limits.knowledgeMaxFiles()) {
            throw new IllegalArgumentException(
                "knowledge contains more Markdown files than allowed."
            );
        }

        List<KnowledgeDocument> documents = new ArrayList<>();
        long totalBytes = 0L;
        for (Path markdownFile : markdownFiles) {
            String filename =
                markdownFile.getFileName().toString();
            Path realFile = requireInsideRoot(
                markdownFile,
                realRoot,
                "knowledge Markdown file"
            );

            if (Files.isDirectory(realFile)) {
                continue;
            }
            if (!Files.isRegularFile(realFile)) {
                throw new IllegalArgumentException(
                    "A knowledge Markdown entry is not a regular file."
                );
            }
            if (!Files.isReadable(realFile)) {
                throw new IllegalArgumentException(
                    "A knowledge Markdown file is not readable."
                );
            }

            LoadedText loaded = readUtf8(
                realFile,
                limits.knowledgeMaxFileBytes(),
                "knowledge Markdown file"
            );
            totalBytes += loaded.byteCount();
            if (totalBytes > limits.knowledgeMaxTotalBytes()) {
                throw new IllegalArgumentException(
                    "Total knowledge Markdown content exceeds the configured limit."
                );
            }

            documents.add(
                new KnowledgeDocument(
                    filename,
                    loaded.content()
                )
            );
        }
        return List.copyOf(documents);
    }

    private boolean isMarkdownEntry(Path path) {
        String filename = path.getFileName().toString();
        if (
            !filename.toLowerCase(Locale.ROOT)
                .endsWith(".md")
        ) {
            return false;
        }

        if (
            Files.isSymbolicLink(path)
                && !Files.exists(path)
        ) {
            throw new IllegalArgumentException(
                "A knowledge Markdown symbolic link is broken."
            );
        }

        return !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private String normalizedFilename(Path path) {
        return Normalizer.normalize(
            path.getFileName().toString(),
            Normalizer.Form.NFC
        ).toLowerCase(Locale.ROOT);
    }

    private Path requireInsideRoot(
        Path path,
        Path realRoot,
        String label
    ) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(configRoot)) {
            throw new IllegalArgumentException(
                label + " escapes the JARVIS configuration root."
            );
        }

        Path real = realPath(
            normalized,
            "Could not resolve " + label + "."
        );
        if (!real.startsWith(realRoot)) {
            throw new IllegalArgumentException(
                label + " resolves outside the JARVIS configuration root."
            );
        }
        return real;
    }

    private Path realPath(
        Path path,
        String error
    ) {
        try {
            return path.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException(error, failure);
        }
    }

    private LoadedText readUtf8(
        Path path,
        int maxBytes,
        String label
    ) {
        byte[] bytes = readBounded(path, maxBytes, label);
        try {
            String content = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                )
                .decode(ByteBuffer.wrap(bytes))
                .toString();
            return new LoadedText(content, bytes.length);
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(
                label + " must contain valid UTF-8.",
                failure
            );
        }
    }

    private byte[] readBounded(
        Path path,
        int maxBytes,
        String label
    ) {
        try (
            InputStream input = Files.newInputStream(path);
            ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                    Math.min(maxBytes, READ_BUFFER_BYTES)
                )
        ) {
            byte[] buffer = new byte[READ_BUFFER_BYTES];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalArgumentException(
                        label + " exceeds the configured byte limit."
                    );
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Could not read " + label + ".",
                failure
            );
        }
    }

    private record LoadedText(
        String content,
        int byteCount
    ) {
    }

    public record Limits(
        int personaMaxBytes,
        int knowledgeMaxFiles,
        int knowledgeMaxFileBytes,
        int knowledgeMaxTotalBytes
    ) {
        public static final int DEFAULT_PERSONA_MAX_BYTES =
            32 * 1024;
        public static final int DEFAULT_KNOWLEDGE_MAX_FILES = 32;
        public static final int DEFAULT_KNOWLEDGE_MAX_FILE_BYTES =
            32 * 1024;
        public static final int DEFAULT_KNOWLEDGE_MAX_TOTAL_BYTES =
            128 * 1024;

        public Limits {
            requirePositive(
                personaMaxBytes,
                "personaMaxBytes"
            );
            requirePositive(
                knowledgeMaxFiles,
                "knowledgeMaxFiles"
            );
            requirePositive(
                knowledgeMaxFileBytes,
                "knowledgeMaxFileBytes"
            );
            requirePositive(
                knowledgeMaxTotalBytes,
                "knowledgeMaxTotalBytes"
            );
            if (
                knowledgeMaxFileBytes
                    > knowledgeMaxTotalBytes
            ) {
                throw new IllegalArgumentException(
                    "knowledgeMaxFileBytes must not exceed knowledgeMaxTotalBytes."
                );
            }
        }

        public static Limits defaults() {
            return new Limits(
                DEFAULT_PERSONA_MAX_BYTES,
                DEFAULT_KNOWLEDGE_MAX_FILES,
                DEFAULT_KNOWLEDGE_MAX_FILE_BYTES,
                DEFAULT_KNOWLEDGE_MAX_TOTAL_BYTES
            );
        }

        private static void requirePositive(
            int value,
            String field
        ) {
            if (value <= 0) {
                throw new IllegalArgumentException(
                    field + " must be positive."
                );
            }
        }
    }
}
