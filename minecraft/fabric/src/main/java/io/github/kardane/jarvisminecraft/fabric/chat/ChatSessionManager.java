package io.github.kardane.jarvisminecraft.fabric.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatSessionManager {
    public static final long TTL_MILLIS = 120_000L;

    private static final Pattern INVOCATION = Pattern.compile(
        "^(?:(?i:jarvis)|자비스|재비스)(?=$|[\\s,:;.!?。！？])"
    );

    private final Clock clock;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public ChatSessionManager(Clock clock) {
        this.clock = clock;
    }

    public synchronized Decision accept(UUID requesterUuid, boolean currentOperator, String message) {
        Instant now = clock.instant();
        Session existing = sessions.get(requesterUuid);
        if (existing != null && !now.isBefore(existing.expiresAt())) {
            sessions.remove(requesterUuid);
            existing = null;
        }

        if (!currentOperator) {
            if (existing != null) {
                sessions.remove(requesterUuid);
            }
            return Decision.publicChat(message);
        }

        Matcher invocation = INVOCATION.matcher(message);
        if (invocation.find()) {
            UUID sessionId = UUID.randomUUID();
            sessions.put(requesterUuid, new Session(sessionId, now.plusMillis(TTL_MILLIS)));
            return Decision.forward(sessionId, "DIRECT", message, true);
        }

        if (existing == null) {
            return Decision.publicChat(message);
        }

        if ("대화 끝".equals(message.trim())) {
            sessions.remove(requesterUuid);
            return Decision.end(existing.sessionId());
        }

        if (message.startsWith("!")) {
            String escaped = message.substring(1);
            if (escaped.isBlank()) {
                escaped = message;
            }
            return Decision.publicEscape(existing.sessionId(), escaped);
        }

        Session refreshed = new Session(existing.sessionId(), now.plusMillis(TTL_MILLIS));
        sessions.put(requesterUuid, refreshed);
        return Decision.forward(refreshed.sessionId(), "FOLLOW_UP", message, false);
    }

    public synchronized void invalidate(UUID requesterUuid) {
        sessions.remove(requesterUuid);
    }

    public synchronized List<SessionHandle> pruneExpired() {
        Instant now = clock.instant();
        List<SessionHandle> removed = new ArrayList<>();
        for (Map.Entry<UUID, Session> entry : List.copyOf(sessions.entrySet())) {
            if (!now.isBefore(entry.getValue().expiresAt())) {
                sessions.remove(entry.getKey());
                removed.add(new SessionHandle(entry.getKey(), entry.getValue().sessionId()));
            }
        }
        return List.copyOf(removed);
    }

    public synchronized List<UUID> pruneInvalid(Predicate<UUID> stillAuthorized) {
        List<UUID> removed = new ArrayList<>();
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            if (!stillAuthorized.test(uuid)) {
                sessions.remove(uuid);
                removed.add(uuid);
            }
        }
        return List.copyOf(removed);
    }

    public synchronized boolean isActive(UUID requesterUuid, UUID sessionId) {
        Session session = sessions.get(requesterUuid);
        if (session == null) {
            return false;
        }
        if (!clock.instant().isBefore(session.expiresAt())) {
            sessions.remove(requesterUuid);
            return false;
        }
        return session.sessionId().equals(sessionId);
    }

    private record Session(UUID sessionId, Instant expiresAt) {}

    public record SessionHandle(UUID requesterUuid, UUID sessionId) {}

    public enum Kind {
        PUBLIC_CHAT,
        PUBLIC_ESCAPE,
        FORWARD,
        END
    }

    public record Decision(
        Kind kind,
        UUID sessionId,
        String mode,
        String text,
        boolean started
    ) {
        static Decision publicChat(String text) {
            return new Decision(Kind.PUBLIC_CHAT, null, null, text, false);
        }

        static Decision publicEscape(UUID sessionId, String text) {
            return new Decision(Kind.PUBLIC_ESCAPE, sessionId, null, text, false);
        }

        static Decision forward(UUID sessionId, String mode, String text, boolean started) {
            return new Decision(Kind.FORWARD, sessionId, mode, text, started);
        }

        static Decision end(UUID sessionId) {
            return new Decision(Kind.END, sessionId, null, "", false);
        }
    }
}
