package io.github.kardane.jarvisminecraft.fabric.platform;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class MinecraftFabricPlatformAccess implements FabricPlatformAccess {
    private static final String PRIVATE_PREFIX = "[JARVIS] ";

    private final MinecraftServer server;

    public MinecraftFabricPlatformAccess(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean isServerThread() {
        return server.isOnThread();
    }

    @Override
    public boolean isOnlineOperator(UUID playerUuid) {
        requireServerThread();
        PlayerManager manager = server.getPlayerManager();
        ServerPlayerEntity player = manager.getPlayer(playerUuid);
        return player != null && manager.isOperator(player.getGameProfile());
    }

    @Override
    public Optional<PlayerSnapshot> findOnlinePlayer(UUID playerUuid) {
        requireServerThread();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
        return player == null ? Optional.empty() : Optional.of(snapshot(player));
    }

    @Override
    public Optional<PlayerSnapshot> findOnlinePlayerExact(String exactName) {
        requireServerThread();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(exactName);
        if (player == null || !player.getGameProfile().getName().equals(exactName)) {
            return Optional.empty();
        }
        return Optional.of(snapshot(player));
    }

    @Override
    public List<PlayerSnapshot> onlinePlayers() {
        requireServerThread();
        return server.getPlayerManager()
            .getPlayerList()
            .stream()
            .map(this::snapshot)
            .sorted(Comparator.comparing(snapshot -> snapshot.uuid().toString()))
            .toList();
    }

    @Override
    public Optional<WorldSnapshot> findLoadedWorld(String worldId) {
        requireServerThread();
        return findWorld(worldId).map(this::worldSnapshot);
    }

    @Override
    public ServerStatusSnapshot serverStatus() {
        requireServerThread();

        long[] samples = server.getTickTimes();
        long sum = 0L;
        int count = 0;
        for (long sample : samples) {
            if (sample > 0L) {
                sum += sample;
                count += 1;
            }
        }

        double averageNanos = count == 0
            ? Math.max(0L, server.getAverageNanosPerTick())
            : (double) sum / count;
        double tpsEstimate = averageNanos <= 0
            ? 20.0
            : Math.min(20.0, 1_000_000_000.0 / Math.max(50_000_000.0, averageNanos));

        int loadedChunks = 0;
        for (ServerWorld world : server.getWorlds()) {
            loadedChunks += world.getChunkManager().getLoadedChunkCount();
        }

        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();

        return new ServerStatusSnapshot(
            tpsEstimate,
            server.getAverageTickTime(),
            server.getPlayerManager().getPlayerList().size(),
            loadedChunks,
            used,
            runtime.maxMemory()
        );
    }

    @Override
    public List<NearbyPlayerSnapshot> nearbyPlayers(
        LocationSnapshot center,
        double radius,
        int limit
    ) {
        requireServerThread();
        Optional<ServerWorld> found = findWorld(center.worldId());
        if (found.isEmpty()) {
            return List.of();
        }

        double maxDistanceSquared = radius * radius;
        List<NearbyPlayerSnapshot> output = new ArrayList<>();

        for (ServerPlayerEntity player : found.get().getPlayers()) {
            double dx = player.getX() - center.x();
            double dy = player.getY() - center.y();
            double dz = player.getZ() - center.z();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared <= maxDistanceSquared) {
                output.add(
                    new NearbyPlayerSnapshot(
                        snapshot(player),
                        Math.sqrt(distanceSquared)
                    )
                );
            }
        }

        output.sort(
            Comparator
                .comparingDouble(NearbyPlayerSnapshot::distance)
                .thenComparing(item -> item.player().uuid().toString())
        );
        if (output.size() > limit) {
            return List.copyOf(output.subList(0, limit));
        }
        return List.copyOf(output);
    }

    @Override
    public CompletionStage<TeleportSnapshot> teleportRequesterTo(
        UUID requesterUuid,
        UUID targetPlayerUuid
    ) {
        requireServerThread();
        PlayerManager manager = server.getPlayerManager();
        ServerPlayerEntity requester = manager.getPlayer(requesterUuid);
        ServerPlayerEntity target = manager.getPlayer(targetPlayerUuid);

        if (
            requester == null
                || target == null
                || !manager.isOperator(requester.getGameProfile())
        ) {
            return CompletableFuture.completedFuture(
                new TeleportSnapshot(
                    requesterUuid,
                    targetPlayerUuid,
                    "",
                    "",
                    false
                )
            );
        }

        String fromWorld = worldId(requester.getWorld());
        String toWorld = worldId(target.getWorld());
        boolean completed = requester.teleport(
            target.getWorld(),
            target.getX(),
            target.getY(),
            target.getZ(),
            Set.of(),
            target.getYaw(),
            target.getPitch(),
            true
        );

        return CompletableFuture.completedFuture(
            new TeleportSnapshot(
                requesterUuid,
                targetPlayerUuid,
                fromWorld,
                toWorld,
                completed
            )
        );
    }

    @Override
    public void sendPrivatePlain(UUID requesterUuid, String text) {
        requireServerThread();
        PlayerManager manager = server.getPlayerManager();
        ServerPlayerEntity player = manager.getPlayer(requesterUuid);
        if (player == null || !manager.isOperator(player.getGameProfile())) {
            return;
        }
        player.sendMessage(Text.literal(PRIVATE_PREFIX + text), false);
    }

    private Optional<ServerWorld> findWorld(String worldId) {
        for (ServerWorld world : server.getWorlds()) {
            String canonical = worldId(world);
            if (
                canonical.equals(worldId)
                    || world.getRegistryKey().getValue().getPath().equals(worldId)
            ) {
                return Optional.of(world);
            }
        }
        return Optional.empty();
    }

    private WorldSnapshot worldSnapshot(ServerWorld world) {
        return new WorldSnapshot(
            worldId(world),
            world.getDimensionEntry().getIdAsString(),
            world.getPlayers().size(),
            world.getDifficulty().getName(),
            world.getTimeOfDay()
        );
    }

    private PlayerSnapshot snapshot(ServerPlayerEntity player) {
        return new PlayerSnapshot(
            player.getUuid(),
            player.getGameProfile().getName(),
            true,
            new LocationSnapshot(
                worldId(player.getWorld()),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYaw(),
                player.getPitch()
            )
        );
    }

    private String worldId(ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    private void requireServerThread() {
        if (!server.isOnThread()) {
            throw new IllegalStateException("Fabric server API accessed off the server thread.");
        }
    }
}
