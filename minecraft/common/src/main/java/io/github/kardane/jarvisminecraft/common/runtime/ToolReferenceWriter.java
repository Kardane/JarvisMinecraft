package io.github.kardane.jarvisminecraft.common.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class ToolReferenceWriter {
    public static final String FILE_NAME = "tools.md";

    private ToolReferenceWriter() {
    }

    public static CompletionStage<Void> writeAsync(
        Path configRoot,
        Set<ToolName> registeredTools
    ) {
        Path root = Objects.requireNonNull(
            configRoot,
            "configRoot"
        ).toAbsolutePath().normalize();
        Set<ToolName> registered = Set.copyOf(
            Objects.requireNonNull(
                registeredTools,
                "registeredTools"
            )
        );

        return CompletableFuture.runAsync(
            () -> write(root, registered)
        );
    }

    private static void write(
        Path root,
        Set<ToolName> registered
    ) {
        try {
            Files.createDirectories(root);
            Path output = root.resolve(FILE_NAME);
            if (
                Files.exists(
                    output,
                    LinkOption.NOFOLLOW_LINKS
                )
                    && Files.isSymbolicLink(output)
            ) {
                throw new IllegalStateException(
                    "Generated Tool reference must not be a symbolic link."
                );
            }

            Path temporary = root.resolve(
                "." + FILE_NAME + ".tmp-" + UUID.randomUUID()
            );
            try {
                Files.writeString(
                    temporary,
                    render(registered),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                );
                replace(temporary, output);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Could not write generated Tool reference.",
                failure
            );
        }
    }

    private static void replace(
        Path temporary,
        Path output
    ) throws IOException {
        try {
            Files.move(
                temporary,
                output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                temporary,
                output,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String render(
        Set<ToolName> registered
    ) {
        StringBuilder output = new StringBuilder();
        output.append("# JARVIS Tool Reference\n\n");
        output.append(
            "This file is generated on server startup and may be overwritten. "
        );
        output.append(
            "Actual Tool exposure still depends on requester OP authority, route, "
        );
        output.append(
            "execution policy, scheduling policy, and optional provider availability.\n\n"
        );
        output.append(
            "| Tool | Runtime source | Capability | Risk | State changing |\n"
        );
        output.append(
            "|---|---|---|---|---|\n"
        );

        java.util.Arrays.stream(ToolName.values())
            .sorted(
                Comparator.comparing(
                    ToolName::wireName
                )
            )
            .forEach(tool -> {
                output.append("| ")
                    .append(tool.wireName())
                    .append(" | ")
                    .append(source(tool, registered))
                    .append(" | ")
                    .append(tool.capability())
                    .append(" | ")
                    .append(tool.risk().name())
                    .append(" | ")
                    .append(tool.stateChanging() ? "yes" : "no")
                    .append(" |\n");
            });

        output.append("\n");
        output.append(
            "registered means the platform/provider registered the Tool on this "
        );
        output.append(
            "server. openai-built-in means it is executed by the OpenAI Responses "
        );
        output.append(
            "API. brain-control means it is handled by the Embedded Brain and "
        );
        output.append(
            "is still policy-gated. not-registered means the required platform or "
        );
        output.append(
            "optional provider capability is currently unavailable.\n"
        );
        return output.toString();
    }

    private static String source(
        ToolName tool,
        Set<ToolName> registered
    ) {
        if (tool == ToolName.WEB_SEARCH) {
            return "openai-built-in";
        }
        if (
            tool == ToolName.SCHEDULE_ACTION
                || tool == ToolName.CANCEL_SCHEDULED_ACTION
        ) {
            return "brain-control";
        }
        return registered.contains(tool)
            ? "registered"
            : "not-registered";
    }
}
