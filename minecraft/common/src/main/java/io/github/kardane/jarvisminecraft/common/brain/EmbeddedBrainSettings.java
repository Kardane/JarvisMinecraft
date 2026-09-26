package io.github.kardane.jarvisminecraft.common.brain;

import java.nio.file.Path;
import java.util.Objects;

public record EmbeddedBrainSettings(
    String serverId,
    String openAiApiKey,
    String typesafeApiKey,
    Path auditDirectory
) {
    public EmbeddedBrainSettings {
        serverId = ServerIdentity.requireValid(serverId, "serverId");
        openAiApiKey = requireSecret(openAiApiKey, "OPENAI_API_KEY");
        typesafeApiKey = requireSecret(typesafeApiKey, "TYPESAFE_API_KEY");
        auditDirectory = Objects.requireNonNull(
            auditDirectory,
            "auditDirectory"
        );
    }

    public static EmbeddedBrainSettings resolve(
        String serverIdOverride,
        String openAiApiKey,
        String typesafeApiKey,
        Path dataDirectory
    ) {
        Path directory = Objects.requireNonNull(
            dataDirectory,
            "dataDirectory"
        ).normalize();

        return new EmbeddedBrainSettings(
            ServerIdentity.resolve(serverIdOverride, directory),
            openAiApiKey,
            typesafeApiKey,
            directory.resolve("audit")
        );
    }

    private static String requireSecret(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
