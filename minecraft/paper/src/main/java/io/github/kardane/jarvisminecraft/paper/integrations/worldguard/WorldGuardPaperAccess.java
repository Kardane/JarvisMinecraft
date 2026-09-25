package io.github.kardane.jarvisminecraft.paper.integrations.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.LocalPlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import io.github.kardane.jarvisminecraft.common.protocol.Protocol.BuildDecision;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/** Thin adapter over the public WorldGuard API and its required WorldEdit bridge. */
public final class WorldGuardPaperAccess implements WorldGuardRegionProvider.Access {
    private final Server server;
    private final Plugin worldGuardPlugin;
    private final WorldGuardPlugin worldGuardApiPlugin;

    private WorldGuardPaperAccess(Server server, Plugin worldGuardPlugin, WorldGuardPlugin worldGuardApiPlugin) {
        this.server = server;
        this.worldGuardPlugin = worldGuardPlugin;
        this.worldGuardApiPlugin = worldGuardApiPlugin;
    }

    public static Optional<WorldGuardPaperAccess> discover(Server server) {
        try {
            Plugin worldGuard = server.getPluginManager().getPlugin("WorldGuard");
            Plugin worldEdit = server.getPluginManager().getPlugin("WorldEdit");
            if (!(worldGuard instanceof WorldGuardPlugin worldGuardApiPlugin)
                || !worldGuard.isEnabled()
                || worldEdit == null
                || !worldEdit.isEnabled()
                || !WorldGuardDependencyCheck.isAvailable(worldGuard.isEnabled(), worldEdit.isEnabled())) {
                return Optional.empty();
            }
            return Optional.of(new WorldGuardPaperAccess(server, worldGuard, worldGuardApiPlugin));
        } catch (LinkageError | RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public boolean worldGuardEnabled() {
        return worldGuardPlugin.isEnabled();
    }

    @Override
    public boolean worldEditEnabled() {
        Plugin worldEdit = server.getPluginManager().getPlugin("WorldEdit");
        return worldEdit != null && worldEdit.isEnabled();
    }

    @Override
    public Optional<List<WorldGuardRegionProvider.RegionView>> regionsAt(ToolModels.Location location) {
        org.bukkit.World world = resolveLoadedWorld(location.worldId());
        if (world == null) {
            return Optional.empty();
        }
        Location adapted = adapt(location, world);
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        ApplicableRegionSet applicable = query.getApplicableRegions(adapted);
        List<WorldGuardRegionProvider.RegionView> result = new ArrayList<>();
        for (ProtectedRegion region : applicable) {
            result.add(regionView(region, false));
        }
        return Optional.of(List.copyOf(result));
    }

    @Override
    public Optional<WorldGuardRegionProvider.RegionView> regionInfo(String worldId, String regionId) {
        org.bukkit.World bukkitWorld = resolveLoadedWorld(worldId);
        if (bukkitWorld == null) {
            return Optional.empty();
        }
        World world = BukkitAdapter.adapt(bukkitWorld);
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager manager = container.get(world);
        if (manager == null) {
            return Optional.empty();
        }
        ProtectedRegion region = manager.getRegion(regionId);
        return region == null ? Optional.empty() : Optional.of(regionView(region, false));
    }

    @Override
    public Optional<WorldGuardRegionProvider.PermissionView> checkBuild(
        UUID playerUuid,
        ToolModels.Location location
    ) {
        org.bukkit.World bukkitWorld = resolveLoadedWorld(location.worldId());
        if (bukkitWorld == null) {
            return Optional.empty();
        }
        Location adapted = adapt(location, bukkitWorld);
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        ApplicableRegionSet applicable = query.getApplicableRegions(adapted);
        List<String> matched = applicable.getRegions().stream()
            .map(ProtectedRegion::getId)
            .toList();

        Player player = server.getPlayer(playerUuid);
        if (player == null) {
            return Optional.of(new WorldGuardRegionProvider.PermissionView(
                BuildDecision.UNDEFINED,
                false,
                matched,
                "Player is offline; bypass permissions cannot be evaluated.",
                false
            ));
        }

        LocalPlayer localPlayer = worldGuardApiPlugin.wrapPlayer(player);
        World world = BukkitAdapter.adapt(bukkitWorld);
        boolean bypass = WorldGuard.getInstance()
            .getPlatform()
            .getSessionManager()
            .hasBypass(localPlayer, world);
        StateFlag.State state = query.queryValue(adapted, localPlayer, Flags.BUILD);
        BuildDecision decision = state == StateFlag.State.ALLOW
            ? BuildDecision.ALLOW
            : state == StateFlag.State.DENY
                ? BuildDecision.DENY
                : BuildDecision.UNDEFINED;
        String reason = bypass
            ? "WorldGuard bypass permission is active."
            : switch (decision) {
                case ALLOW -> "WorldGuard build flag allows this player.";
                case DENY -> "WorldGuard build flag denies this player.";
                case UNDEFINED -> "WorldGuard has no applicable build decision.";
            };
        return Optional.of(new WorldGuardRegionProvider.PermissionView(
            decision,
            bypass,
            matched,
            reason,
            false
        ));
    }

    private WorldGuardRegionProvider.RegionView regionView(ProtectedRegion region, boolean truncated) {
        Map<String, Object> flags = new LinkedHashMap<>();
        for (Map.Entry<Flag<?>, ?> entry : region.getFlags().entrySet()) {
            Object value = entry.getValue();
            if (entry.getKey() != null && value != null) {
                // The protocol carries only scalar flag values. String conversion also covers
                // WorldGuard's State and collection-valued flags without exporting API objects.
                flags.put(entry.getKey().getName(), value instanceof Enum<?> enumValue
                    ? enumValue.name()
                    : String.valueOf(value));
            }
        }
        ProtectedRegion parent = region.getParent();
        return new WorldGuardRegionProvider.RegionView(
            region.getId(),
            region.getPriority(),
            parent == null ? null : parent.getId(),
            domainMembers(region.getOwners()),
            domainMembers(region.getMembers()),
            flags,
            truncated
        );
    }

    private List<String> domainMembers(DefaultDomain domain) {
        TreeSet<String> sorted = new TreeSet<>();
        for (UUID uuid : domain.getUniqueIds()) {
            sorted.add(uuid.toString());
        }
        for (String player : domain.getPlayers()) {
            if (player != null && !player.isBlank()) {
                sorted.add(player);
            }
        }
        for (String group : domain.getGroups()) {
            if (group != null && !group.isBlank()) {
                sorted.add("group:" + group);
            }
        }
        return List.copyOf(sorted);
    }

    private org.bukkit.World resolveLoadedWorld(String worldId) {
        try {
            return server.getWorld(UUID.fromString(worldId));
        } catch (IllegalArgumentException ignored) {
            return server.getWorld(worldId);
        }
    }

    private Location adapt(ToolModels.Location location, org.bukkit.World world) {
        org.bukkit.Location bukkitLocation = new org.bukkit.Location(
            world,
            location.x(),
            location.y(),
            location.z(),
            (float) location.yaw(),
            (float) location.pitch()
        );
        return BukkitAdapter.adapt(bukkitLocation);
    }
}
