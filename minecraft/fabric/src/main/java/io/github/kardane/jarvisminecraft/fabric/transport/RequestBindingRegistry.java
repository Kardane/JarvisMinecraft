package io.github.kardane.jarvisminecraft.fabric.transport;

import io.github.kardane.jarvisminecraft.common.protocol.ProtocolMessage;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RequestBindingRegistry {
    private final Clock clock;
    private final Map<UUID, Binding> requests = new HashMap<>();

    public RequestBindingRegistry(Clock clock) {
        this.clock = clock;
    }

    public synchronized void register(
        UUID requestId,
        UUID requesterUuid,
        UUID sessionId,
        Instant deadlineAt
    ) {
        prune();
        requests.put(requestId, new Binding(requestId, requesterUuid, sessionId, deadlineAt));
    }

    public synchronized boolean matches(ProtocolMessage message) {
        prune();
        if (message.requestId() == null || message.requesterUuid() == null || message.sessionId() == null) {
            return false;
        }
        Binding binding = requests.get(message.requestId());
        return binding != null
            && binding.requesterUuid().equals(message.requesterUuid())
            && binding.sessionId().equals(message.sessionId())
            && clock.instant().isBefore(binding.deadlineAt());
    }

    public synchronized void complete(UUID requestId) {
        requests.remove(requestId);
    }

    public synchronized List<UUID> invalidateActor(UUID requesterUuid) {
        List<UUID> removed = new ArrayList<>();
        for (Binding binding : List.copyOf(requests.values())) {
            if (binding.requesterUuid().equals(requesterUuid)) {
                requests.remove(binding.requestId());
                removed.add(binding.requestId());
            }
        }
        return List.copyOf(removed);
    }

    public synchronized List<UUID> invalidateSession(UUID requesterUuid, UUID sessionId) {
        List<UUID> removed = new ArrayList<>();
        for (Binding binding : List.copyOf(requests.values())) {
            if (binding.requesterUuid().equals(requesterUuid) && binding.sessionId().equals(sessionId)) {
                requests.remove(binding.requestId());
                removed.add(binding.requestId());
            }
        }
        return List.copyOf(removed);
    }

    public synchronized void clear() {
        requests.clear();
    }

    private void prune() {
        Instant now = clock.instant();
        requests.values().removeIf(binding -> !now.isBefore(binding.deadlineAt()));
    }

    private record Binding(
        UUID requestId,
        UUID requesterUuid,
        UUID sessionId,
        Instant deadlineAt
    ) {}
}
