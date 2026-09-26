package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.brain.ai.OpenAiLunaClient;
import io.github.kardane.jarvisminecraft.common.protocol.ToolArgumentCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CI-only clean-server boot marker used by E16 packaging verification.
 */
public final class PackagingSmoke {
    public static final String ENABLE_PROPERTY = "jarvis.e16BootSmoke";
    public static final String MARKER_PROPERTY = "jarvis.e16BootMarker";

    private PackagingSmoke() {
    }

    public static boolean requested() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    public static void mark(String platform) {
        verifyEmbeddedSdkRuntime();

        String marker = System.getProperty(MARKER_PROPERTY);
        if (marker == null || marker.isBlank()) {
            throw new IllegalStateException(
                "E16 boot smoke marker path is missing."
            );
        }

        Path path = Path.of(marker).toAbsolutePath().normalize();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                path,
                "E16 " + platform + " clean boot OK\n",
                StandardCharsets.UTF_8
            );
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Could not write E16 boot smoke marker.",
                failure
            );
        }
    }

    private static void verifyEmbeddedSdkRuntime() {
        OpenAiLunaClient client = new OpenAiLunaClient(
            "e16-packaging-smoke-key",
            new ToolArgumentCodec()
        );
        try {
            if (!"gpt-6-luna".equals(client.modelId())) {
                throw new IllegalStateException(
                    "E16 OpenAI SDK smoke resolved an unexpected Luna model."
                );
            }
        } finally {
            client.close();
        }
    }
}
