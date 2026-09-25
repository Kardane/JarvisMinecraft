package io.github.kardane.jarvisminecraft.paper.integrations.worldguard;

import io.github.kardane.jarvisminecraft.common.protocol.Protocol.BuildDecision;
import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus;
import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.BuildPermissionArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.BuildPermissionData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.Location;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.RegionInfoArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.RegionInfoData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.RegionSummary;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.RegionsAtLocationArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.RegionsAtLocationData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Synchronous, read-only WorldGuard queries intended to run on Paper's primary thread. */
public final class WorldGuardRegionProvider {
    public static final String SOURCE = "WorldGuard";

    private static final int MAX_REGIONS = 100;
    private static final int MAX_MEMBERS = 100;
    private static final int MAX_FLAGS = 128;

    private final Access access;
    private final Clock clock;

    public WorldGuardRegionProvider(Access access, Clock clock) {
        this.access = Objects.requireNonNull(access, "access");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void registerTools(ToolRegistry registry) {
        registry.register(
            ToolName.GET_REGIONS_AT_LOCATION,
            RegionsAtLocationArguments.class,
            (context, arguments) -> java.util.concurrent.CompletableFuture.completedFuture(regionsAt(arguments))
        );
        registry.register(
            ToolName.GET_REGION_INFO,
            RegionInfoArguments.class,
            (context, arguments) -> java.util.concurrent.CompletableFuture.completedFuture(regionInfo(arguments))
        );
        registry.register(
            ToolName.CHECK_BUILD_PERMISSION,
            BuildPermissionArguments.class,
            (context, arguments) -> java.util.concurrent.CompletableFuture.completedFuture(checkBuild(arguments))
        );
    }

    public ToolResult regionsAt(RegionsAtLocationArguments arguments) {
        if (arguments == null || !validLocation(arguments.location())) {
            return error(ErrorCode.INVALID_ARGUMENT, "Invalid region location.", false);
        }
        if (!available()) {
            return error(ErrorCode.PROVIDER_UNAVAILABLE, "WorldGuard or its WorldEdit dependency is unavailable.", true);
        }
        try {
            Optional<List<RegionView>> query = access.regionsAt(arguments.location());
            if (query == null) {
                return error(ErrorCode.PROVIDER_UNAVAILABLE, "WorldGuard returned no query response.", true);
            }
            if (query.isEmpty()) {
                return error(ErrorCode.NOT_FOUND, "The requested world is not loaded.", false);
            }

            List<RegionSummary> summaries = new ArrayList<>();
            boolean truncated = false;
            for (RegionView region : query.get()) {
                if (region == null) {
                    truncated = true;
                    continue;
                }
                if (summaries.size() >= MAX_REGIONS) {
                    truncated = true;
                    break;
                }
                BoundedStrings owners = boundedStrings(region.owners(), MAX_MEMBERS, 64);
                BoundedStrings members = boundedStrings(region.members(), MAX_MEMBERS, 64);
                summaries.add(new RegionSummary(region.id(), region.priority(), owners.values(), members.values()));
                truncated |= region.truncated() || owners.truncated() || members.truncated();
            }
            RegionsAtLocationData data = new RegionsAtLocationData(List.copyOf(summaries));
            return success(summaries.isEmpty() ? ResultStatus.EMPTY : ResultStatus.OK, data, truncated);
        } catch (RuntimeException failure) {
            return error(ErrorCode.PROVIDER_UNAVAILABLE, "WorldGuard region query failed.", true);
        }
    }

    public ToolResult regionInfo(RegionInfoArguments arguments) {
        if (arguments == null || arguments.worldId() == null || arguments.worldId().isBlank()
            || arguments.regionId() == null || arguments.regionId().isBlank()
            || arguments.worldId().length() > 128 || arguments.regionId().length() > 128) {
            return error(ErrorCode.INVALID_ARGUMENT, "Invalid region selector.", false);
        }
        if (!available()) {
            return error(ErrorCode.PROVIDER_UNAVAILABLE, "WorldGuard or its WorldEdit dependency is unavailable.", true);
        }
        try {
            Optional<RegionView> query = access.regionInfo(arguments.worldId(), arguments.regionId());
            if (query == null) {
                return error(ErrorCode.PROVIDER_UNAVAILABLE, "WorldGuard returned no region response.", true);
            }
            if (query.isEmpty()) {
                return error(ErrorCode.NOT_FOUND, "The world or region was not found.", false);
            }
            RegionView region = query.get();
            BoundedStrings owners = boundedStrings(region.owners(), MAX_MEMBERS, 64);
            BoundedStrings members = boundedStrings(region.members(), MAX_MEMBERS, 64);
            BoundedFlags flags = boundedFlags(region.flags());
            RegionInfoData data = new RegionInfoData(
                region.id(),
                arguments.worldId(),
                region.priority(),
                region.parentId(),
                owners.values(),
                members.values(),
                flags.values()
            );
            return success(ResultStatus.OK, data,
                region.truncated() || owners.truncated() || members.truncated() || flags.truncated());
        } catch (RuntimeException failure) {
            return error(ErrorCode.PROVIDER_UNAVAILABLE, "WorldGuard region detail query failed.", true);
        }
    }

    public ToolResult checkBuild(BuildPermissionArguments arguments) {
        if (arguments == null || arguments.playerUuid() == null || !validLocation(arguments.location())) {
            return error(ErrorCode.INVALID_ARGUMENT, "Invalid build permission query.", false);
        }
        if (!available()) {
            return error(ErrorCode.PROVIDER_UNAVAILABLE, "WorldGuard or its WorldEdit dependency is unavailable.", true);
        }
        try {
            Optional<PermissionView> query = access.checkBuild(arguments.playerUuid(), arguments.location());
            if (query == null) {
                return error(ErrorCode.PROVIDER_UNAVAILABLE, "WorldGuard returned no permission response.", true);
            }
            if (query.isEmpty()) {
                return error(ErrorCode.NOT_FOUND, "The requested world was not found.", false);
            }
            PermissionView result = query.get();
            BoundedStrings regions = boundedStrings(result.matchedRegions(), MAX_REGIONS, 128);
            BuildDecision decision = result.bypass()
                ? BuildDecision.ALLOW
                : result.decision() == null ? BuildDecision.UNDEFINED : result.decision();
            String reason = boundedReason(result.reason());
            return success(
                ResultStatus.OK,
                new BuildPermissionData(decision, result.bypass(), regions.values(), reason),
                result.truncated() || regions.truncated() || !Objects.equals(reason, result.reason())
            );
        } catch (RuntimeException failure) {
            return error(ErrorCode.PROVIDER_UNAVAILABLE, "WorldGuard protection query failed.", true);
        }
    }

    private boolean available() {
        try {
            return WorldGuardDependencyCheck.isAvailable(access.worldGuardEnabled(), access.worldEditEnabled());
        } catch (LinkageError | RuntimeException failure) {
            return false;
        }
    }

    private boolean validLocation(Location location) {
        return location != null
            && location.worldId() != null
            && !location.worldId().isBlank()
            && location.worldId().length() <= 128
            && Double.isFinite(location.x())
            && Double.isFinite(location.y())
            && Double.isFinite(location.z());
    }

    private BoundedStrings boundedStrings(List<String> values, int maxValues, int maxLength) {
        if (values == null || values.isEmpty()) {
            return new BoundedStrings(List.of(), false);
        }
        List<String> output = new ArrayList<>();
        boolean truncated = false;
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > maxLength) {
                truncated = true;
                continue;
            }
            if (output.size() >= maxValues) {
                truncated = true;
                break;
            }
            output.add(value);
        }
        return new BoundedStrings(List.copyOf(output), truncated);
    }

    private BoundedFlags boundedFlags(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return new BoundedFlags(Map.of(), false);
        }
        Map<String, Object> sorted = new java.util.TreeMap<>();
        boolean truncated = false;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String name = entry.getKey();
            Object value = scalar(entry.getValue());
            if (name == null || name.isBlank() || name.length() > 128 || value == null) {
                truncated = true;
                continue;
            }
            if (sorted.size() >= MAX_FLAGS && !sorted.containsKey(name)) {
                truncated = true;
                continue;
            }
            sorted.put(name, value);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        sorted.forEach(output::put);
        return new BoundedFlags(Map.copyOf(output), truncated);
    }

    private Object scalar(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return String.valueOf(value);
    }

    private String boundedReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "WorldGuard build flag evaluated.";
        }
        return reason.length() <= 512 ? reason : reason.substring(0, 512);
    }

    private ToolResult success(ResultStatus status, io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolData data, boolean truncated) {
        return new ToolResult(status, data, null, clock.instant(), SOURCE, truncated);
    }

    private ToolResult error(ErrorCode code, String message, boolean retryable) {
        return ToolResult.error(code, message, retryable, clock.instant(), SOURCE);
    }

    public interface Access {
        boolean worldGuardEnabled();

        boolean worldEditEnabled();

        /** Empty means the world is not loaded; a present empty list means no applicable regions. */
        Optional<List<RegionView>> regionsAt(Location location);

        /** Empty means the world or region does not exist. */
        Optional<RegionView> regionInfo(String worldId, String regionId);

        /** Empty means the world does not exist. Offline players should return UNDEFINED explicitly. */
        Optional<PermissionView> checkBuild(UUID playerUuid, Location location);
    }

    public record RegionView(
        String id,
        int priority,
        String parentId,
        List<String> owners,
        List<String> members,
        Map<String, Object> flags,
        boolean truncated
    ) {
        public RegionView {
            owners = owners == null ? List.of() : List.copyOf(owners);
            members = members == null ? List.of() : List.copyOf(members);
            flags = flags == null ? Map.of() : Map.copyOf(flags);
        }
    }

    public record PermissionView(
        BuildDecision decision,
        boolean bypass,
        List<String> matchedRegions,
        String reason,
        boolean truncated
    ) {
        public PermissionView {
            matchedRegions = matchedRegions == null ? List.of() : List.copyOf(matchedRegions);
        }
    }

    private record BoundedStrings(List<String> values, boolean truncated) {
    }

    private record BoundedFlags(Map<String, Object> values, boolean truncated) {
    }
}
