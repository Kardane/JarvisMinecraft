package io.github.kardane.jarvisminecraft.common.brain.ai;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class DeterministicRoutePolicy {
    private static final Map<JevCategory, Set<ToolName>> ROUTE_TOOLS;

    static {
        EnumMap<JevCategory, Set<ToolName>> routes = new EnumMap<>(JevCategory.class);
        routes.put(
            JevCategory.SERVER_QUERY,
            EnumSet.of(ToolName.GET_SERVER_STATUS, ToolName.GET_ONLINE_PLAYERS)
        );
        routes.put(
            JevCategory.PLAYER_QUERY,
            EnumSet.of(
                ToolName.GET_ONLINE_PLAYERS,
                ToolName.GET_PLAYER,
                ToolName.GET_PLAYER_LOCATION,
                ToolName.GET_NEARBY_PLAYERS
            )
        );
        routes.put(
            JevCategory.WORLD_QUERY,
            EnumSet.of(ToolName.GET_WORLD_INFO, ToolName.GET_NEARBY_PLAYERS)
        );
        routes.put(
            JevCategory.HISTORY_QUERY,
            EnumSet.of(ToolName.LOOKUP_AREA_HISTORY, ToolName.LOOKUP_PLAYER_HISTORY)
        );
        routes.put(
            JevCategory.REGION_QUERY,
            EnumSet.of(
                ToolName.GET_REGIONS_AT_LOCATION,
                ToolName.GET_REGION_INFO,
                ToolName.CHECK_BUILD_PERMISSION
            )
        );
        routes.put(
            JevCategory.ACTION_REQUEST,
            EnumSet.of(
                ToolName.GET_PLAYER,
                ToolName.GET_PLAYER_LOCATION,
                ToolName.TELEPORT_STAFF
            )
        );
        routes.put(JevCategory.GENERAL, EnumSet.noneOf(ToolName.class));
        routes.put(JevCategory.UNCERTAIN, EnumSet.noneOf(ToolName.class));
        ROUTE_TOOLS = Collections.unmodifiableMap(routes);
    }

    private final Double abstainBelow;

    public DeterministicRoutePolicy() {
        this(null);
    }

    public DeterministicRoutePolicy(Double abstainBelow) {
        if (
            abstainBelow != null
                && (
                    !Double.isFinite(abstainBelow)
                        || abstainBelow < 0.0
                        || abstainBelow > 1.0
                )
        ) {
            throw new IllegalArgumentException(
                "Jev confidence threshold must be between 0 and 1."
            );
        }
        this.abstainBelow = abstainBelow;
    }

    public RoutingDecision route(
        JevClassification classification,
        Set<ToolName> activeTools
    ) {
        Objects.requireNonNull(classification, "classification");
        Set<ToolName> active = Set.copyOf(Objects.requireNonNull(activeTools, "activeTools"));

        if (classification.category() == JevCategory.UNCERTAIN) {
            return fallback(
                classification.category(),
                FallbackReason.JEV_UNCERTAIN,
                active
            );
        }
        if (
            abstainBelow != null
                && classification.confidence() < abstainBelow
        ) {
            return fallback(
                classification.category(),
                FallbackReason.JEV_LOW_CONFIDENCE,
                active
            );
        }

        EnumSet<ToolName> routed = EnumSet.noneOf(ToolName.class);
        for (ToolName tool : ROUTE_TOOLS.get(classification.category())) {
            if (active.contains(tool)) {
                routed.add(tool);
            }
        }
        return new RoutingDecision(
            classification.category(),
            null,
            Set.copyOf(routed)
        );
    }

    public RoutingDecision errorFallback(Set<ToolName> activeTools) {
        return fallback(
            JevCategory.UNCERTAIN,
            FallbackReason.JEV_ERROR,
            Set.copyOf(Objects.requireNonNull(activeTools, "activeTools"))
        );
    }

    private RoutingDecision fallback(
        JevCategory category,
        FallbackReason reason,
        Set<ToolName> activeTools
    ) {
        EnumSet<ToolName> readOnly = EnumSet.noneOf(ToolName.class);
        for (ToolName tool : activeTools) {
            if (!tool.stateChanging()) {
                readOnly.add(tool);
            }
        }
        return new RoutingDecision(category, reason, Set.copyOf(readOnly));
    }

    public enum FallbackReason {
        JEV_ERROR,
        JEV_UNCERTAIN,
        JEV_LOW_CONFIDENCE
    }

    public record RoutingDecision(
        JevCategory category,
        FallbackReason fallbackReason,
        Set<ToolName> availableTools
    ) {
        public RoutingDecision {
            Objects.requireNonNull(category, "category");
            availableTools =
                Set.copyOf(Objects.requireNonNull(availableTools, "availableTools"));
        }

        public boolean fallbackActive() {
            return fallbackReason != null;
        }
    }
}
