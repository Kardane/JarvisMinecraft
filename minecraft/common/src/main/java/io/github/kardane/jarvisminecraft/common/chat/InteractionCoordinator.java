package io.github.kardane.jarvisminecraft.common.chat;

import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class InteractionCoordinator {
    private final ChatSessionManager sessions;
    private final ConfigManager configManager;
    private final AudiencePolicy audiencePolicy;
    private final InvocationMatcher invocationMatcher;

    public InteractionCoordinator(
        ChatSessionManager sessions,
        ConfigManager configManager
    ) {
        this(
            sessions,
            configManager,
            new AudiencePolicy(),
            new InvocationMatcher()
        );
    }

    public InteractionCoordinator(
        ChatSessionManager sessions,
        ConfigManager configManager,
        AudiencePolicy audiencePolicy,
        InvocationMatcher invocationMatcher
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.configManager = Objects.requireNonNull(
            configManager,
            "configManager"
        );
        this.audiencePolicy = Objects.requireNonNull(
            audiencePolicy,
            "audiencePolicy"
        );
        this.invocationMatcher = Objects.requireNonNull(
            invocationMatcher,
            "invocationMatcher"
        );
    }

    public InteractionDecision accept(
        PlayerIdentity player,
        String message
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");

        JarvisConfig.Interaction config =
            configManager.current().interaction();

        if (!audiencePolicy.allows(config.audience(), player)) {
            boolean revoked =
                sessions.activeSession(player.uuid()).isPresent();
            sessions.invalidate(player.uuid());
            return InteractionDecision.publicChat(message, revoked);
        }

        Duration ttl = Duration.ofSeconds(config.followUpSeconds());

        Optional<InvocationMatcher.Match> invocation =
            invocationMatcher.find(
                message,
                config.wakeWords(),
                config.wakeWordMatching()
            );
        if (invocation.isPresent()) {
            InvocationMatcher.Match match =
                invocation.get();
            UUID sessionId = sessions.start(
                player.uuid(),
                ttl
            );
            String requestText =
                match.remainingText().isBlank()
                    ? message
                    : match.remainingText();
            return InteractionDecision.forward(
                sessionId,
                "DIRECT",
                requestText,
                true,
                config.followUpSeconds()
            );
        }

        Optional<UUID> existing = sessions.activeSession(player.uuid());
        if (existing.isEmpty()) {
            return InteractionDecision.publicChat(message, false);
        }

        UUID sessionId = existing.get();

        if ("대화 끝".equals(message.trim())) {
            sessions.end(player.uuid(), sessionId);
            return InteractionDecision.end(sessionId);
        }

        if (message.startsWith("!")) {
            String escaped = message.substring(1);
            if (escaped.isBlank()) {
                escaped = message;
            }
            return InteractionDecision.publicEscape(sessionId, escaped);
        }

        return InteractionDecision.followUpCandidate(
            sessionId,
            message,
            config.followUpSeconds()
        );
    }

    /**
     * Returns the runtime-scoped configuration manager backing interaction policy.
     * Composition roots must share this same instance with the Brain policies.
     */
    public ConfigManager configManager() {
        return configManager;
    }

    public boolean isAuthorized(PlayerIdentity player) {
        Objects.requireNonNull(player, "player");
        return audiencePolicy.allows(
            configManager.current().interaction().audience(),
            player
        );
    }

    public List<UUID> pruneInvalid(
        Function<UUID, Optional<PlayerIdentity>> resolver
    ) {
        Objects.requireNonNull(resolver, "resolver");
        return sessions.pruneInvalid(
            uuid -> resolver.apply(uuid)
                .map(this::isAuthorized)
                .orElse(false)
        );
    }
}
