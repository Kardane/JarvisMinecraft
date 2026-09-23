package io.github.kardane.jarvisminecraft.common.protocol;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.BuildDecision;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus;

public final class ToolModels {
    private ToolModels() {
    }

    public sealed interface ToolArguments permits
        NoArguments, PagingArguments, GetPlayerByUuidArguments, GetPlayerByNameArguments,
        PlayerUuidArguments, NearbyArguments, WorldInfoArguments, TeleportArguments,
        AreaHistoryArguments, PlayerHistoryArguments, RegionsAtLocationArguments,
        RegionInfoArguments, BuildPermissionArguments {
    }

    public sealed interface ToolData permits
        ServerStatusData, OnlinePlayersData, PlayerData, PlayerLocationData,
        NearbyPlayersData, WorldInfoData, TeleportData, HistoryData,
        RegionsAtLocationData, RegionInfoData, BuildPermissionData {
    }

    public record NoArguments() implements ToolArguments {
    }

    public record PagingArguments(String cursor, int limit) implements ToolArguments {
    }

    public record GetPlayerByUuidArguments(UUID playerUuid) implements ToolArguments {
    }

    public record GetPlayerByNameArguments(String exactName) implements ToolArguments {
    }

    public record PlayerUuidArguments(UUID playerUuid) implements ToolArguments {
    }

    public record NearbyArguments(Location center, double radius, int limit) implements ToolArguments {
    }

    public record WorldInfoArguments(String worldId) implements ToolArguments {
    }

    public record TeleportArguments(UUID targetPlayerUuid) implements ToolArguments {
    }

    public record AreaHistoryArguments(
        Location center,
        int radius,
        int lookbackSeconds,
        String cursor,
        int limit
    ) implements ToolArguments {
    }

    public record PlayerHistoryArguments(
        UUID playerUuid,
        int lookbackSeconds,
        String cursor,
        int limit
    ) implements ToolArguments {
    }

    public record RegionsAtLocationArguments(Location location) implements ToolArguments {
    }

    public record RegionInfoArguments(String worldId, String regionId) implements ToolArguments {
    }

    public record BuildPermissionArguments(UUID playerUuid, Location location) implements ToolArguments {
    }

    public record PlayerRef(UUID uuid, String name) {
    }

    public record Location(
        String worldId,
        double x,
        double y,
        double z,
        double yaw,
        double pitch
    ) {
    }

    public record Metric(
        Double value,
        String unit,
        Long windowMs,
        Instant observedAt,
        String source
    ) {
    }

    public record ServerStatusData(
        Metric tps,
        Metric mspt,
        Metric onlinePlayers,
        Metric loadedChunks,
        Metric memoryUsedBytes,
        Metric memoryMaxBytes
    ) implements ToolData {
    }

    public record OnlinePlayersData(
        List<PlayerRef> players,
        int returnedCount,
        String nextCursor
    ) implements ToolData {
    }

    public record PlayerData(PlayerRef player, boolean online) implements ToolData {
    }

    public record PlayerLocationData(PlayerRef player, Location location) implements ToolData {
    }

    public record NearbyPlayer(PlayerRef player, double distance) {
    }

    public record NearbyPlayersData(List<NearbyPlayer> players, int returnedCount) implements ToolData {
    }

    public record WorldInfoData(
        String worldId,
        String dimensionKey,
        int playerCount,
        String difficulty,
        Long timeOfDay
    ) implements ToolData {
    }

    public record TeleportData(
        UUID requesterUuid,
        UUID targetPlayerUuid,
        String fromWorldId,
        String toWorldId,
        boolean completed
    ) implements ToolData {
    }

    public record BlockChange(
        Instant timestamp,
        UUID actorUuid,
        String actorName,
        String action,
        Location location,
        String material
    ) {
    }

    public record HistoryData(
        List<BlockChange> records,
        int returnedCount,
        Integer totalCount,
        String nextCursor,
        Instant windowStart,
        Instant windowEnd
    ) implements ToolData {
    }

    public record RegionSummary(
        String id,
        int priority,
        List<String> owners,
        List<String> members
    ) {
    }

    public record RegionsAtLocationData(List<RegionSummary> regions) implements ToolData {
    }

    public record RegionInfoData(
        String id,
        String worldId,
        int priority,
        String parentId,
        List<String> owners,
        List<String> members,
        Map<String, Object> flags
    ) implements ToolData {
    }

    public record BuildPermissionData(
        BuildDecision decision,
        boolean bypass,
        List<String> matchedRegions,
        String reason
    ) implements ToolData {
    }

    public record ErrorObject(
        ErrorCode code,
        String message,
        boolean retryable,
        Map<String, Object> details
    ) {
        public ErrorObject {
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public static ErrorObject of(ErrorCode code, String message, boolean retryable) {
            return new ErrorObject(code, message, retryable, Map.of());
        }
    }

    public record ToolResult(
        ResultStatus status,
        ToolData data,
        ErrorObject error,
        Instant observedAt,
        String source,
        boolean truncated
    ) {
        public static ToolResult ok(ToolData data, Instant observedAt, String source) {
            return new ToolResult(ResultStatus.OK, data, null, observedAt, source, false);
        }

        public static ToolResult error(
            ErrorCode code,
            String message,
            boolean retryable,
            Instant observedAt,
            String source
        ) {
            return new ToolResult(
                ResultStatus.ERROR,
                null,
                ErrorObject.of(code, message, retryable),
                observedAt,
                source,
                false
            );
        }
    }
}
