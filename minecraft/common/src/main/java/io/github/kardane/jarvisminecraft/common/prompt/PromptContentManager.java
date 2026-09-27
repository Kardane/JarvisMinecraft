package io.github.kardane.jarvisminecraft.common.prompt;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class PromptContentManager {
    private final PromptContentLoader loader;
    private final AtomicReference<PromptContentSnapshot> current;

    public PromptContentManager(PromptContentLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.current = new AtomicReference<>(
            Objects.requireNonNull(
                loader.load(),
                "initial prompt content"
            )
        );
    }

    public PromptContentSnapshot current() {
        return current.get();
    }

    public synchronized ReloadResult reload() {
        PromptContentSnapshot previous = current.get();
        try {
            PromptContentSnapshot next =
                Objects.requireNonNull(
                    loader.load(),
                    "reloaded prompt content"
                );
            current.set(next);
            return ReloadResult.success(
                ContentSummary.from(next)
            );
        } catch (RuntimeException failure) {
            return ReloadResult.failure(
                ContentSummary.from(previous),
                safeMessage(failure)
            );
        }
    }

    private String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "Prompt content reload failed.";
        }
        return message;
    }

    public record ReloadResult(
        boolean success,
        ContentSummary activeContent,
        String error
    ) {
        public ReloadResult {
            Objects.requireNonNull(
                activeContent,
                "activeContent"
            );
            if (success && error != null) {
                throw new IllegalArgumentException(
                    "Successful reload cannot have an error."
                );
            }
            if (
                !success
                    && (error == null || error.isBlank())
            ) {
                throw new IllegalArgumentException(
                    "Failed reload requires an error."
                );
            }
        }

        static ReloadResult success(
            ContentSummary activeContent
        ) {
            return new ReloadResult(
                true,
                activeContent,
                null
            );
        }

        static ReloadResult failure(
            ContentSummary activeContent,
            String error
        ) {
            return new ReloadResult(
                false,
                activeContent,
                error
            );
        }
    }

    public record ContentSummary(
        boolean personaPresent,
        int personaBytes,
        int knowledgeDocuments,
        int knowledgeBytes
    ) {
        static ContentSummary from(
            PromptContentSnapshot snapshot
        ) {
            int personaBytes =
                snapshot.persona()
                    .getBytes(StandardCharsets.UTF_8)
                    .length;
            int knowledgeBytes =
                snapshot.knowledge().stream()
                    .mapToInt(document ->
                        document.content()
                            .getBytes(
                                StandardCharsets.UTF_8
                            )
                            .length
                    )
                    .sum();
            return new ContentSummary(
                !snapshot.persona().isBlank(),
                personaBytes,
                snapshot.knowledge().size(),
                knowledgeBytes
            );
        }
    }
}
