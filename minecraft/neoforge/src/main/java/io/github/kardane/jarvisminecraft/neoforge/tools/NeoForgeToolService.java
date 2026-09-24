package io.github.kardane.jarvisminecraft.neoforge.tools;

import io.github.kardane.jarvisminecraft.common.protocol.ToolModels;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.BuildPermissionArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.GetPlayerByNameArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.GetPlayerByUuidArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.Location;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.Metric;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.NearbyArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.NearbyPlayer;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.NearbyPlayersData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.NoArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.OnlinePlayersData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PagingArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerLocationData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerRef;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.PlayerUuidArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ServerStatusData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.TeleportArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.TeleportData;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolResult;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.WorldInfoArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.WorldInfoData;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry.ToolExecutionContext;
import io.github.kardane.jarvisminecraft.neoforge.platform.NeoForgePlatformAccess;
import io.github.kardane.jarvisminecraft.neoforge.platform.NeoForgePlatformAccess.LocationSnapshot;
import io.github.kardane.jarvisminecraft.neoforge.platform.NeoForgePlatformAccess.PlayerSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class NeoForgeToolService {
    public static final String SOURCE = "NeoForge";

    private final NeoForgePlatformAccess platform;
    private final Clock clock;

    public NeoForgeToolService(NeoForgePlatformAccess platform, Clock clock) {
        this.platform = platform;
        this.clock = clock;
    }

    public void register(ToolRegistry registry) {
        registry.register(
            ToolName.GET_SERVER_STATUS,
            NoArguments.class,
            (context, arguments) -> completed(serverStatus())
        );
        registry.register(
            ToolName.GET_ONLINE_PLAYERS,
            PagingArguments.class,
            (context, arguments) -> completed(onlinePlayers(arguments))
        );
        registry.register(
            ToolName.GET_PLAYER,
            ToolArguments.class,
            (context, arguments) -> completed(player(arguments))
        );
        registry.register(
            ToolName.GET_PLAYER_LOCATION,
            PlayerUuidArguments.class,
            (context, arguments) -> completed(playerLocation(arguments))
        );
        registry.register(
            ToolName.GET_NEARBY_PLAYERS,
            NearbyArguments.class,
            (context, arguments) -> completed(nearbyPlayers(arguments))
        );
        registry.register(
            ToolName.GET_WORLD_INFO,
            WorldInfoArguments.class,
            (context, arguments) -> completed(worldInfo(arguments))
        );
        registry.register(
            ToolName.TELEPORT_STAFF,
            TeleportArguments.class,
            this::teleportStaff
        );
    }

    private ToolResult serverStatus() {
        Instant observedAt = clock.instant();
        NeoForgePlatformAccess.ServerStatusSnapshot status = platform.serverStatus();

        return result(
            ResultStatus.OK,
            new ServerStatusData(
                metric(status.tpsEstimate(), "tps", null, observedAt, "NeoForgeTickSampler"),
                metric(status.msptAverage(), "ms", null, observedAt),
                metric((double) status.onlinePlayers(), "players", null, observedAt),
                metric((double) status.loadedChunks(), "chunks", null, observedAt),
                metric((double) status.memoryUsedBytes(), "bytes", null, observedAt),
                metric((double) status.memoryMaxBytes(), "bytes", null, observedAt)
            ),
            null,
            observedAt,
            false
        );
    }

    private ToolResult onlinePlayers(PagingArguments arguments) {
        List<PlayerSnapshot> snapshots = platform.onlinePlayers();
        int offset;
        try {
            offset = parseCursor(arguments.cursor());
        } catch (IllegalArgumentException failure) {
            return error(ErrorCode.INVALID_ARGUMENT, "Invalid online-player cursor.", false);
        }

        if (offset > snapshots.size()) {
            return error(ErrorCode.INVALID_ARGUMENT, "Online-player cursor is outside the current result set.", false);
        }

        int end = Math.min(snapshots.size(), offset + arguments.limit());
        List<PlayerRef> players = snapshots.subList(offset, end).stream()
            .map(this::playerRef)
            .toList();
        String nextCursor = end < snapshots.size() ? cursor(end) : null;
        Instant observedAt = clock.instant();

        return result(
            players.isEmpty() ? ResultStatus.EMPTY : ResultStatus.OK,
            new OnlinePlayersData(players, players.size(), nextCursor),
            null,
            observedAt,
            nextCursor != null
        );
    }

    private ToolResult player(ToolArguments arguments) {
        Optional<PlayerSnapshot> found;
        if (arguments instanceof GetPlayerByUuidArguments byUuid) {
            found = platform.findOnlinePlayer(byUuid.playerUuid());
        } else if (arguments instanceof GetPlayerByNameArguments byName) {
            found = platform.findOnlinePlayerExact(byName.exactName());
        } else {
            return error(ErrorCode.INVALID_ARGUMENT, "Unsupported player selector.", false);
        }

        if (found.isEmpty()) {
            return error(ErrorCode.NOT_FOUND, "Online player was not found.", false);
        }

        Instant observedAt = clock.instant();
        return result(
            ResultStatus.OK,
            new PlayerData(playerRef(found.get()), true),
            null,
            observedAt,
            false
        );
    }

    private ToolResult playerLocation(PlayerUuidArguments arguments) {
        Optional<PlayerSnapshot> found = platform.findOnlinePlayer(arguments.playerUuid());
        if (found.isEmpty()) {
            return error(ErrorCode.NOT_FOUND, "Online player was not found.", false);
        }

        PlayerSnapshot player = found.get();
        Instant observedAt = clock.instant();
        return result(
            ResultStatus.OK,
            new PlayerLocationData(
                playerRef(player),
                location(player.location())
            ),
            null,
            observedAt,
            false
        );
    }

    private ToolResult nearbyPlayers(NearbyArguments arguments) {
        if (platform.findLoadedWorld(arguments.center().worldId()).isEmpty()) {
            return error(ErrorCode.NOT_FOUND, "Loaded world was not found.", false);
        }

        List<NeoForgePlatformAccess.NearbyPlayerSnapshot> nearby = platform.nearbyPlayers(
            snapshot(arguments.center()),
            arguments.radius(),
            arguments.limit()
        );
        List<NearbyPlayer> players = nearby.stream()
            .map(item -> new NearbyPlayer(playerRef(item.player()), item.distance()))
            .toList();

        Instant observedAt = clock.instant();
        return result(
            players.isEmpty() ? ResultStatus.EMPTY : ResultStatus.OK,
            new NearbyPlayersData(players, players.size()),
            null,
            observedAt,
            false
        );
    }

    private ToolResult worldInfo(WorldInfoArguments arguments) {
        Optional<NeoForgePlatformAccess.WorldSnapshot> found =
            platform.findLoadedWorld(arguments.worldId());
        if (found.isEmpty()) {
            return error(ErrorCode.NOT_FOUND, "Loaded world was not found.", false);
        }

        NeoForgePlatformAccess.WorldSnapshot world = found.get();
        Instant observedAt = clock.instant();
        return result(
            ResultStatus.OK,
            new WorldInfoData(
                world.worldId(),
                world.dimensionKey(),
                world.playerCount(),
                world.difficulty(),
                world.timeOfDay()
            ),
            null,
            observedAt,
            false
        );
    }

    private CompletionStage<ToolResult> teleportStaff(
        ToolExecutionContext context,
        TeleportArguments arguments
    ) {
        if (!platform.isOnlineOperator(context.requesterUuid())) {
            return completed(
                error(ErrorCode.UNAUTHORIZED, "Requester is no longer an online operator.", false)
            );
        }
        if (platform.findOnlinePlayer(arguments.targetPlayerUuid()).isEmpty()) {
            return completed(
                error(ErrorCode.NOT_FOUND, "Teleport target is not online.", false)
            );
        }

        return platform
            .teleportRequesterTo(context.requesterUuid(), arguments.targetPlayerUuid())
            .handle((teleport, failure) -> {
                if (failure != null) {
                    return error(ErrorCode.INTERNAL, "NeoForge teleport failed.", false);
                }
                if (!teleport.completed()) {
                    return error(ErrorCode.CANCELLED, "NeoForge teleport was cancelled or not completed.", false);
                }
                Instant observedAt = clock.instant();
                return result(
                    ResultStatus.OK,
                    new TeleportData(
                        teleport.requesterUuid(),
                        teleport.targetPlayerUuid(),
                        teleport.fromWorldId(),
                        teleport.toWorldId(),
                        true
                    ),
                    null,
                    observedAt,
                    false
                );
            });
    }

    private Metric metric(
        double value,
        String unit,
        Long windowMs,
        Instant observedAt
    ) {
        return metric(value, unit, windowMs, observedAt, SOURCE);
    }

    private Metric metric(
        double value,
        String unit,
        Long windowMs,
        Instant observedAt,
        String source
    ) {
        return new Metric(value, unit, windowMs, observedAt, source);
    }

    private PlayerRef playerRef(PlayerSnapshot player) {
        return new PlayerRef(player.uuid(), player.name());
    }

    private Location location(LocationSnapshot value) {
        return new Location(
            value.worldId(),
            value.x(),
            value.y(),
            value.z(),
            value.yaw(),
            value.pitch()
        );
    }

    private LocationSnapshot snapshot(Location value) {
        return new LocationSnapshot(
            value.worldId(),
            value.x(),
            value.y(),
            value.z(),
            value.yaw(),
            value.pitch()
        );
    }

    private ToolResult error(ErrorCode code, String message, boolean retryable) {
        Instant observedAt = clock.instant();
        return ToolResult.error(code, message, retryable, observedAt, SOURCE);
    }

    private ToolResult result(
        ResultStatus status,
        ToolModels.ToolData data,
        ToolModels.ErrorObject error,
        Instant observedAt,
        boolean truncated
    ) {
        return new ToolResult(
            status,
            data,
            error,
            observedAt,
            SOURCE,
            truncated
        );
    }

    private CompletionStage<ToolResult> completed(ToolResult result) {
        return CompletableFuture.completedFuture(result);
    }

    private int parseCursor(String cursor) {
        if (cursor == null) {
            return 0;
        }
        if (!cursor.startsWith("p:")) {
            throw new IllegalArgumentException("cursor");
        }
        int value = Integer.parseInt(cursor.substring(2));
        if (value < 0) {
            throw new IllegalArgumentException("cursor");
        }
        return value;
    }

    private String cursor(int offset) {
        return "p:" + offset;
    }
}
