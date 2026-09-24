package io.github.kardane.jarvisminecraft.paper.platform;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class BukkitPaperPlatformAccess implements PaperPlatformAccess {
    private static final String PRIVATE_PREFIX = "[JARVIS] ";

    private final Server server;

    public BukkitPaperPlatformAccess(Server server) {
        this.server = server;
    }

    @Override
    public boolean isServerThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public boolean isOnlineOperator(UUID playerUuid) {
        requireServerThread();
        Player player = server.getPlayer(playerUuid);
        return player != null && player.isOnline() && player.isOp();
    }

    @Override
    public Optional<PlayerSnapshot> findOnlinePlayer(UUID playerUuid) {
        requireServerThread();
        Player player = server.getPlayer(playerUuid);
        return player == null || !player.isOnline()
            ? Optional.empty()
            : Optional.of(snapshot(player));
    }

    @Override
    public Optional<PlayerSnapshot> findOnlinePlayerExact(String exactName) {
        requireServerThread();
        Player player = server.getPlayerExact(exactName);
        return player == null || !player.isOnline()
            ? Optional.empty()
            : Optional.of(snapshot(player));
    }

    @Override
    public List<PlayerSnapshot> onlinePlayers() {
        requireServerThread();
        return server.getOnlinePlayers().stream()
            .filter(Player::isOnline)
            .map(this::snapshot)
            .sorted(Comparator.comparing(snapshot -> snapshot.uuid().toString()))
            .toList();
    }

    @Override
    public Optional<WorldSnapshot> findLoadedWorld(String worldId) {
        requireServerThread();
        World world = server.getWorld(worldId);
        if (world == null) {
            return Optional.empty();
        }
        return Optional.of(
            new WorldSnapshot(
                world.getName(),
                world.getKey().toString(),
                world.getPlayers().size(),
                world.getDifficulty().name(),
                world.getTime()
            )
        );
    }

    @Override
    public ServerStatusSnapshot serverStatus() {
        requireServerThread();
        double[] tps = server.getTPS();
        int loadedChunks = 0;
        for (World world : server.getWorlds()) {
            loadedChunks += world.getLoadedChunks().length;
        }

        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return new ServerStatusSnapshot(
            tps.length == 0 ? 0.0 : tps[0],
            server.getAverageTickTime(),
            server.getOnlinePlayers().size(),
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
        World world = server.getWorld(center.worldId());
        if (world == null) {
            return List.of();
        }

        Location origin = new Location(
            world,
            center.x(),
            center.y(),
            center.z(),
            (float) center.yaw(),
            (float) center.pitch()
        );
        double maxDistanceSquared = radius * radius;
        List<NearbyPlayerSnapshot> output = new ArrayList<>();

        for (Player player : world.getPlayers()) {
            if (!player.isOnline()) {
                continue;
            }
            Location current = player.getLocation();
            double distanceSquared = origin.distanceSquared(current);
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
        Player requester = server.getPlayer(requesterUuid);
        Player target = server.getPlayer(targetPlayerUuid);
        if (
            requester == null
                || !requester.isOnline()
                || !requester.isOp()
                || target == null
                || !target.isOnline()
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

        String fromWorld = requester.getWorld().getName();
        Location targetLocation = target.getLocation();
        String toWorld = targetLocation.getWorld().getName();

        return requester
            .teleportAsync(targetLocation, TeleportCause.PLUGIN)
            .thenApply(
                completed ->
                    new TeleportSnapshot(
                        requesterUuid,
                        targetPlayerUuid,
                        fromWorld,
                        toWorld,
                        Boolean.TRUE.equals(completed)
                    )
            );
    }

    @Override
    public void sendPrivatePlain(UUID requesterUuid, String text) {
        requireServerThread();
        Player player = server.getPlayer(requesterUuid);
        if (player == null || !player.isOnline() || !player.isOp()) {
            return;
        }
        player.sendMessage(Component.text(PRIVATE_PREFIX + text));
    }

    private PlayerSnapshot snapshot(Player player) {
        Location location = player.getLocation();
        return new PlayerSnapshot(
            player.getUniqueId(),
            player.getName(),
            player.isOnline(),
            new LocationSnapshot(
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
            )
        );
    }

    private void requireServerThread() {
        if (!isServerThread()) {
            throw new IllegalStateException("Paper server API accessed off the primary thread.");
        }
    }
}
