package io.github.kardane.jarvisminecraft.neoforge.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class MinecraftNeoForgePlatformAccess implements NeoForgePlatformAccess {
    private static final String PRIVATE_PREFIX = "[JARVIS] ";

    private final MinecraftServer server;

    public MinecraftNeoForgePlatformAccess(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean isServerThread() {
        return server.isSameThread();
    }

    @Override
    public boolean isOnlineOperator(UUID playerUuid) {
        requireServerThread();
        PlayerList manager = server.getPlayerList();
        ServerPlayer player = manager.getPlayer(playerUuid);
        return player != null && manager.isOp(player.getGameProfile());
    }

    @Override
    public Optional<PlayerSnapshot> findOnlinePlayer(UUID playerUuid) {
        requireServerThread();
        ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
        return player == null ? Optional.empty() : Optional.of(snapshot(player));
    }

    @Override
    public Optional<PlayerSnapshot> findOnlinePlayerExact(String exactName) {
        requireServerThread();
        ServerPlayer player = server.getPlayerList().getPlayerByName(exactName);
        if (player == null || !player.getGameProfile().getName().equals(exactName)) {
            return Optional.empty();
        }
        return Optional.of(snapshot(player));
    }

    @Override
    public List<PlayerSnapshot> onlinePlayers() {
        requireServerThread();
        return server.getPlayerList()
            .getPlayers()
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

        double mspt = Math.max(0.0, server.getAverageTickTime());
        double tpsEstimate = mspt <= 0.0
            ? 20.0
            : Math.min(20.0, 1000.0 / Math.max(50.0, mspt));

        int loadedChunks = 0;
        for (ServerLevel world : server.getAllLevels()) {
            loadedChunks += world.getChunkSource().getLoadedChunksCount();
        }

        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();

        return new ServerStatusSnapshot(
            tpsEstimate,
            mspt,
            server.getPlayerList().getPlayers().size(),
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
        Optional<ServerLevel> found = findWorld(center.worldId());
        if (found.isEmpty()) {
            return List.of();
        }

        double maxDistanceSquared = radius * radius;
        List<NearbyPlayerSnapshot> output = new ArrayList<>();

        for (ServerPlayer player : found.get().players()) {
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
        PlayerList manager = server.getPlayerList();
        ServerPlayer requester = manager.getPlayer(requesterUuid);
        ServerPlayer target = manager.getPlayer(targetPlayerUuid);

        if (
            requester == null
                || target == null
                || !manager.isOp(requester.getGameProfile())
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

        String fromWorld = worldId(requester.serverLevel());
        String toWorld = worldId(target.serverLevel());
        boolean completed = requester.teleportTo(
            target.serverLevel(),
            target.getX(),
            target.getY(),
            target.getZ(),
            Set.of(),
            target.getYRot(),
            target.getXRot(),
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
        PlayerList manager = server.getPlayerList();
        ServerPlayer player = manager.getPlayer(requesterUuid);
        if (player == null || !manager.isOp(player.getGameProfile())) {
            return;
        }
        player.sendSystemMessage(Component.literal(PRIVATE_PREFIX + text), false);
    }

    private Optional<ServerLevel> findWorld(String worldId) {
        for (ServerLevel world : server.getAllLevels()) {
            String canonical = worldId(world);
            if (
                canonical.equals(worldId)
                    || world.dimension().location().getPath().equals(worldId)
            ) {
                return Optional.of(world);
            }
        }
        return Optional.empty();
    }

    private WorldSnapshot worldSnapshot(ServerLevel world) {
        return new WorldSnapshot(
            worldId(world),
            world.dimensionTypeRegistration().getRegisteredName(),
            world.players().size(),
            world.getDifficulty().getKey(),
            world.getDayTime()
        );
    }

    private PlayerSnapshot snapshot(ServerPlayer player) {
        return new PlayerSnapshot(
            player.getUUID(),
            player.getGameProfile().getName(),
            true,
            new LocationSnapshot(
                worldId(player.serverLevel()),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot()
            )
        );
    }

    private String worldId(ServerLevel world) {
        return world.dimension().location().toString();
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("NeoForge server API accessed off the server thread.");
        }
    }
}
