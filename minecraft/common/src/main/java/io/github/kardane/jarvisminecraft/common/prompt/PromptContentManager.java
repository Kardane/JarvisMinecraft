package io.github.kardane.jarvisminecraft.common.prompt;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class PromptContentManager {
    private final Supplier<PromptContentSnapshot> currentSource;
    private final Supplier<ReloadResult> reloadOperation;

    public PromptContentManager(PromptContentLoader loader) {
        Objects.requireNonNull(loader, "loader");
        AtomicReference<PromptContentSnapshot> state =
            new AtomicReference<>(
                Objects.requireNonNull(
                    loader.load(),
                    "initial prompt content"
                )
            );
        this.currentSource = state::get;
        this.reloadOperation =
            () -> reloadStandalone(loader, state);
    }

    private PromptContentManager(
        Supplier<PromptContentSnapshot> currentSource,
        Supplier<ReloadResult> reloadOperation
    ) {
        this.currentSource = Objects.requireNonNull(
            currentSource,
            "currentSource"
        );
        this.reloadOperation = Objects.requireNonNull(
            reloadOperation,
            "reloadOperation"
        );
    }

    public static PromptContentManager managed(
        Supplier<PromptContentSnapshot> currentSource,
        Supplier<ReloadResult> reloadOperation
    ) {
        return new PromptContentManager(
            currentSource,
            reloadOperation
        );
    }

    public PromptContentSnapshot current() {
        return Objects.requireNonNull(
            currentSource.get(),
            "current prompt content"
        );
    }

    public synchronized ReloadResult reload() {
        return Objects.requireNonNull(
            reloadOperation.get(),
            "reload result"
        );
    }

    private static ReloadResult reloadStandalone(
        PromptContentLoader loader,
        AtomicReference<PromptContentSnapshot> state
    ) {
        PromptContentSnapshot previous = state.get();
        try {
            PromptContentSnapshot next =
                Objects.requireNonNull(
                    loader.load(),
                    "reloaded prompt content"
                );
            state.set(next);
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

    private static String safeMessage(
        RuntimeException failure
    ) {
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

        public static ReloadResult success(
            ContentSummary activeContent
        ) {
            return new ReloadResult(
                true,
                activeContent,
                null
            );
        }

        public static ReloadResult failure(
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
        public static ContentSummary from(
            PromptContentSnapshot snapshot
        ) {
            Objects.requireNonNull(snapshot, "snapshot");
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
