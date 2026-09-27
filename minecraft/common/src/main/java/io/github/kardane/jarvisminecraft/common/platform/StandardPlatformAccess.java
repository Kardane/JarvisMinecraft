package io.github.kardane.jarvisminecraft.common.platform;

import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.WeatherType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface StandardPlatformAccess extends AdapterPlatformAccess {
    Optional<PlayerSnapshot> findOnlinePlayer(UUID playerUuid);

    Optional<PlayerSnapshot> findOnlinePlayerExact(String exactName);

    List<PlayerSnapshot> onlinePlayers();

    Optional<WorldSnapshot> findLoadedWorld(String worldId);

    ServerStatusSnapshot serverStatus();

    List<NearbyPlayerSnapshot> nearbyPlayers(LocationSnapshot center, double radius, int limit);

    CompletionStage<TeleportSnapshot> teleportRequesterTo(UUID requesterUuid, UUID targetPlayerUuid);

    default Optional<WeatherMutationSnapshot> setWeather(
        String worldId,
        WeatherType weather,
        int durationSeconds
    ) {
        return Optional.empty();
    }

    default Optional<TimeMutationSnapshot> setTimeOfDay(
        String worldId,
        int timeOfDay
    ) {
        return Optional.empty();
    }

    record PlayerSnapshot(UUID uuid, String name, boolean online, LocationSnapshot location) {}

    record LocationSnapshot(
        String worldId,
        double x,
        double y,
        double z,
        double yaw,
        double pitch
    ) {}

    record NearbyPlayerSnapshot(PlayerSnapshot player, double distance) {}

    record WorldSnapshot(
        String worldId,
        String dimensionKey,
        int playerCount,
        String difficulty,
        long timeOfDay
    ) {}

    record ServerStatusSnapshot(
        double tps,
        double msptAverage,
        int onlinePlayers,
        int loadedChunks,
        long memoryUsedBytes,
        long memoryMaxBytes
    ) {}

    record TeleportSnapshot(
        UUID requesterUuid,
        UUID targetPlayerUuid,
        String fromWorldId,
        String toWorldId,
        boolean completed
    ) {}

    record WeatherMutationSnapshot(
        String worldId,
        WeatherType weather,
        int durationSeconds,
        boolean completed
    ) {}

    record TimeMutationSnapshot(
        String worldId,
        int timeOfDay,
        boolean completed
    ) {}
}
