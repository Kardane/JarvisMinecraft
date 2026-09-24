package io.github.kardane.jarvisminecraft.common.runtime;

import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ActionState;

public final class DeduplicationLedger {
    private final int maxActions;
    private final LinkedHashMap<UUID, ActionRecord> actions;

    public DeduplicationLedger(int maxActions) {
        if (maxActions < 1) {
            throw new IllegalArgumentException("maxActions must be positive");
        }
        this.maxActions = maxActions;
        this.actions = new LinkedHashMap<>(16, 0.75f, true);
    }

    public synchronized ActionStart beginAction(UUID actionId, UUID connectionId) {
        ActionRecord existing = actions.get(actionId);
        if (existing == null) {
            actions.put(actionId, new ActionRecord(connectionId, ActionState.EXECUTING, null));
            evictTerminalActions();
            return ActionStart.fresh();
        }
        if (!existing.connectionId().equals(connectionId)) {
            return ActionStart.staleConnection();
        }
        if (existing.state() == ActionState.EXECUTING) {
            return ActionStart.inFlight();
        }
        return ActionStart.cached(existing.result());
    }

    public synchronized void completeAction(
        UUID actionId,
        UUID connectionId,
        ActionState state,
        ToolResult result
    ) {
        ActionRecord existing = actions.get(actionId);
        if (existing == null || !existing.connectionId().equals(connectionId)) {
            return;
        }
        if (existing.state() != ActionState.EXECUTING) {
            return;
        }
        actions.put(actionId, new ActionRecord(connectionId, state, result));
        evictTerminalActions();
    }

    private void evictTerminalActions() {
        if (actions.size() <= maxActions) {
            return;
        }
        var iterator = actions.entrySet().iterator();
        while (actions.size() > maxActions && iterator.hasNext()) {
            Map.Entry<UUID, ActionRecord> entry = iterator.next();
            if (entry.getValue().state() != ActionState.EXECUTING) {
                iterator.remove();
            }
        }
    }

    private record ActionRecord(UUID connectionId, ActionState state, ToolResult result) {
    }

    public record ActionStart(Kind kind, ToolResult result) {
        public enum Kind {
            FRESH,
            IN_FLIGHT,
            CACHED,
            STALE_CONNECTION
        }

        static ActionStart fresh() {
            return new ActionStart(Kind.FRESH, null);
        }

        static ActionStart inFlight() {
            return new ActionStart(Kind.IN_FLIGHT, null);
        }

        static ActionStart cached(ToolResult result) {
            return new ActionStart(Kind.CACHED, result);
        }

        static ActionStart staleConnection() {
            return new ActionStart(Kind.STALE_CONNECTION, null);
        }
    }

    public static final class ToolCallLedger {
        private final int maxEntries;
        private final LinkedHashMap<UUID, ToolCallRecord> calls;

        public ToolCallLedger(int maxEntries) {
            if (maxEntries < 1) {
                throw new IllegalArgumentException("maxEntries must be positive");
            }
            this.maxEntries = maxEntries;
            this.calls = new LinkedHashMap<>(16, 0.75f, true);
        }

        public synchronized ToolCallStart begin(UUID toolCallId) {
            ToolCallRecord existing = calls.get(toolCallId);
            if (existing == null) {
                calls.put(toolCallId, new ToolCallRecord(true, null));
                evict();
                return ToolCallStart.fresh();
            }
            if (existing.inFlight()) {
                return ToolCallStart.inFlight();
            }
            return ToolCallStart.cached(existing.result());
        }

        public synchronized void complete(UUID toolCallId, ToolResult result) {
            ToolCallRecord existing = calls.get(toolCallId);
            if (existing == null || !existing.inFlight()) {
                return;
            }
            calls.put(toolCallId, new ToolCallRecord(false, result));
            evict();
        }

        private void evict() {
            if (calls.size() <= maxEntries) {
                return;
            }
            var iterator = calls.entrySet().iterator();
            while (calls.size() > maxEntries && iterator.hasNext()) {
                Map.Entry<UUID, ToolCallRecord> entry = iterator.next();
                if (!entry.getValue().inFlight()) {
                    iterator.remove();
                }
            }
        }

        private record ToolCallRecord(boolean inFlight, ToolResult result) {
        }
    }

    public record ToolCallStart(Kind kind, ToolResult result) {
        public enum Kind {
            FRESH,
            IN_FLIGHT,
            CACHED
        }

        static ToolCallStart fresh() {
            return new ToolCallStart(Kind.FRESH, null);
        }

        static ToolCallStart inFlight() {
            return new ToolCallStart(Kind.IN_FLIGHT, null);
        }

        static ToolCallStart cached(ToolResult result) {
            return new ToolCallStart(Kind.CACHED, result);
        }
    }
}
