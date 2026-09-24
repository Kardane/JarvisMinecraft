package io.github.kardane.jarvisminecraft.common.transport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

public final class SharedSecretAuthenticator {
    private static final Set<String> PLACEHOLDERS = Set.of(
        "change_me",
        "changeme",
        "secret",
        "sample",
        "example"
    );

    private final byte[] expected;

    public SharedSecretAuthenticator(String secret) {
        validateConfiguration(secret);
        this.expected = secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean authenticate(String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8));
    }

    public static void validateConfiguration(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JARVIS shared secret must not be blank.");
        }
        String normalized = secret.trim().toLowerCase(Locale.ROOT);
        if (PLACEHOLDERS.contains(normalized)) {
            throw new IllegalArgumentException("JARVIS shared secret must not use a placeholder value.");
        }
        if (secret.length() < 16) {
            throw new IllegalArgumentException("JARVIS shared secret must be at least 16 characters.");
        }
    }
}
