package io.github.kardane.jarvisminecraft.common.chat;

import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;

import java.util.List;
import java.util.Objects;

public final class AudiencePolicy {
    public boolean allows(
        JarvisConfig.Audience audience,
        PlayerIdentity player
    ) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(player, "player");

        if (!player.online()) {
            return false;
        }

        return switch (audience.mode()) {
            case OP -> player.operator();
            case WHITELIST -> containsName(
                audience.whitelist(),
                player.name()
            );
            case ALL -> true;
            case BLACKLIST -> !containsName(
                audience.blacklist(),
                player.name()
            );
        };
    }

    private boolean containsName(List<String> configured, String playerName) {
        for (String candidate : configured) {
            if (candidate.equalsIgnoreCase(playerName)) {
                return true;
            }
        }
        return false;
    }
}
