package io.github.kardane.jarvisminecraft.common.brain;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

public record EmbeddedBrainSettings(
    String serverId,
    String openAiApiKey,
    String typesafeApiKey,
    Path auditDirectory
) {
    private static final Pattern SERVER_ID =
        Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public EmbeddedBrainSettings {
        if (serverId == null || !SERVER_ID.matcher(serverId).matches()) {
            throw new IllegalArgumentException(
                "serverId must match protocol serverId rules."
            );
        }
        openAiApiKey = requireSecret(openAiApiKey, "OPENAI_API_KEY");
        typesafeApiKey = requireSecret(typesafeApiKey, "TYPESAFE_API_KEY");
        auditDirectory = Objects.requireNonNull(
            auditDirectory,
            "auditDirectory"
        );
    }

    private static String requireSecret(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
