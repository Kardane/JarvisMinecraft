package io.github.kardane.jarvisminecraft.common.chat;

import java.util.Objects;
import java.util.UUID;

public record PlayerIdentity(
    UUID uuid,
    String name,
    boolean online,
    boolean operator
) {
    public PlayerIdentity {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank.");
        }
    }
}
