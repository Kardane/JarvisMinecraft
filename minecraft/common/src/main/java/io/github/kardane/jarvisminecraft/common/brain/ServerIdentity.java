package io.github.kardane.jarvisminecraft.common.brain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;


/**
 * Resolves the stable logical identity for one local Minecraft server.
 *
 * <p>An explicit override wins. Existing persisted IDs are retained for archive
 * compatibility; new installations derive a stable ID from the data path.</p>
 */
public final class ServerIdentity {
    public static final String FILE_NAME = "server-id.txt";

    private static final Pattern SERVER_ID =
        Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private ServerIdentity() {
    }

    public static String resolve(String override, Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");

        String normalizedOverride = normalizeOptional(override);
        if (normalizedOverride != null) {
            return requireValid(normalizedOverride, "serverId override");
        }

        Path identityFile = dataDirectory.resolve(FILE_NAME);
        try {
            if (Files.exists(identityFile)) {
                if (!Files.isRegularFile(identityFile)) {
                    throw new IllegalStateException(
                        "JARVIS server identity path is not a regular file: "
                            + identityFile
                    );
                }
                return read(identityFile);
            }

            String canonicalPath = dataDirectory.toAbsolutePath()
                .normalize().toString();
            return "local-" + UUID.nameUUIDFromBytes(
                canonicalPath.getBytes(StandardCharsets.UTF_8)
            );
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Could not load the JARVIS local server identity.",
                failure
            );
        }
    }

    static String requireValid(String value, String source) {
        if (value == null || !SERVER_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                source + " must match [A-Za-z0-9._-]{1,64}."
            );
        }
        return value;
    }

    private static String read(Path identityFile) throws IOException {
        String value = Files.readString(
            identityFile,
            StandardCharsets.UTF_8
        ).trim();
        try {
            return requireValid(value, "Persisted serverId");
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                "Persisted JARVIS server identity is invalid: " + identityFile,
                invalid
            );
        }
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
