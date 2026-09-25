package io.github.kardane.jarvisminecraft.paper.integrations.coreprotect;

import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.BlockChange;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.Location;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.api.result.UsernameResult;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Public CoreProtect API v12 adapter. Construct only after optional plugin discovery succeeds. */
public final class CoreProtectPaperAccess implements CoreProtectHistoryProvider.Backend {
    private static final List<Integer> BLOCK_ACTIONS = List.of(0, 1);
    private static final int MAX_PLAYER_ALIASES = 32;

    private final Server server;
    private final Plugin plugin;
    private final CoreProtectAPI api;

    private CoreProtectPaperAccess(Server server, Plugin plugin, CoreProtectAPI api) {
        this.server = server;
        this.plugin = plugin;
        this.api = api;
    }

    public static Optional<CoreProtectPaperAccess> discover(PluginManager plugins, Server server) {
        try {
            Plugin plugin = plugins.getPlugin("CoreProtect");
            if (!(plugin instanceof CoreProtect coreProtect) || !plugin.isEnabled()) {
                return Optional.empty();
            }
            CoreProtectAPI api = coreProtect.getAPI();
            if (api == null || !CoreProtectApiCompatibility.isSupported(
                true,
                true,
                plugin.isEnabled(),
                api.isEnabled(),
                api.APIVersion()
            )) {
                return Optional.empty();
            }
            return Optional.of(new CoreProtectPaperAccess(server, plugin, api));
        } catch (LinkageError | RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled() && api.isEnabled();
    }

    @Override
    public int apiVersion() {
        return api.APIVersion();
    }

    @Override
    public Optional<Object> captureCenter(Location location) {
        World world = resolveLoadedWorld(location.worldId());
        if (world == null) {
            return Optional.empty();
        }
        return Optional.of(new org.bukkit.Location(world, location.x(), location.y(), location.z()));
    }

    @Override
    public CoreProtectHistoryProvider.LookupBatch lookupArea(
        Object capturedCenter,
        Location center,
        int radius,
        int lookbackSeconds
    ) {
        org.bukkit.Location apiCenter = (org.bukkit.Location) capturedCenter;
        // API v12's radius=0 disables radius filtering. Search radius 1 and then keep the exact
        // block coordinates so this Tool's radius=0 contract does not turn into a whole-world query.
        int apiRadius = radius == 0 ? 1 : radius;
        List<String[]> rows = api.performLookup(
            lookbackSeconds,
            null,
            null,
            null,
            null,
            BLOCK_ACTIONS,
            apiRadius,
            apiCenter
        );
        if (rows == null) {
            return null;
        }
        return parseRows(rows, center.worldId(), null, Set.of(), radius == 0, center);
    }

    @Override
    public CoreProtectHistoryProvider.LookupBatch lookupPlayer(UUID playerUuid, int lookbackSeconds) {
        List<UsernameResult> targetHistory = api.usernameLookup(playerUuid.toString(), lookbackSeconds);
        if (targetHistory == null) {
            return null;
        }

        boolean incomplete = false;
        Map<String, String> candidateNames = new LinkedHashMap<>();
        for (UsernameResult result : targetHistory) {
            if (result == null || !playerUuid.toString().equalsIgnoreCase(result.getUuid())) {
                incomplete = true;
                continue;
            }
            String name = result.getUsername();
            if (name != null && !name.isBlank() && name.length() <= 64) {
                candidateNames.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), name);
            } else {
                incomplete = true;
            }
        }

        List<String> uniqueNames = new ArrayList<>();
        for (String name : candidateNames.values()) {
            if (uniqueNames.size() >= MAX_PLAYER_ALIASES) {
                incomplete = true;
                break;
            }
            List<UsernameResult> nameHistory = api.usernameLookup(name, lookbackSeconds);
            if (nameHistory == null) {
                return null;
            }
            Set<String> owners = new LinkedHashSet<>();
            for (UsernameResult owner : nameHistory) {
                if (owner == null || owner.getUuid() == null) {
                    incomplete = true;
                    continue;
                }
                owners.add(owner.getUuid().toLowerCase(java.util.Locale.ROOT));
            }
            if (owners.size() == 1 && owners.contains(playerUuid.toString().toLowerCase(java.util.Locale.ROOT))) {
                uniqueNames.add(name);
            } else {
                // CoreProtect block rows contain player names. Do not attribute a recycled name
                // to multiple UUIDs; omit it and report a partial result instead.
                incomplete = true;
            }
        }

        if (uniqueNames.isEmpty()) {
            String source = "CoreProtect API v12+; UUID history needs an unambiguous logged name";
            return new CoreProtectHistoryProvider.LookupBatch(List.of(), null, true, source);
        }

        List<String[]> rows = api.performLookup(
            lookbackSeconds,
            List.copyOf(uniqueNames),
            null,
            null,
            null,
            BLOCK_ACTIONS,
            0,
            null
        );
        if (rows == null) {
            return null;
        }
        CoreProtectHistoryProvider.LookupBatch parsed = parseRows(
            rows,
            null,
            playerUuid,
            Set.copyOf(uniqueNames.stream().map(name -> name.toLowerCase(java.util.Locale.ROOT)).toList()),
            false,
            null
        );
        boolean partial = incomplete || parsed.incomplete();
        Integer totalCount = partial ? null : parsed.records().size();
        String source = partial
            ? "CoreProtect API v12+; ambiguous or malformed player names omitted"
            : "CoreProtect API v12+; UUID resolved through unique username history";
        return new CoreProtectHistoryProvider.LookupBatch(parsed.records(), totalCount, partial, source);
    }

    private CoreProtectHistoryProvider.LookupBatch parseRows(
        List<String[]> rows,
        String fallbackWorld,
        UUID actorUuid,
        Set<String> allowedNames,
        boolean exactBlock,
        Location exactCenter
    ) {
        List<BlockChange> records = new ArrayList<>();
        boolean incomplete = false;
        for (String[] row : rows) {
            if (row == null) {
                incomplete = true;
                continue;
            }
            try {
                CoreProtectAPI.ParseResult parsed = api.parseResult(row);
                int actionId = parsed.getActionId();
                String action = switch (actionId) {
                    case 0 -> "BREAK";
                    case 1 -> "PLACE";
                    default -> null;
                };
                if (action == null) {
                    incomplete = true;
                    continue;
                }
                int x = parsed.getX();
                int y = parsed.getY();
                int z = parsed.getZ();
                if (exactBlock && (x != floor(exactCenter.x())
                    || y != floor(exactCenter.y()) || z != floor(exactCenter.z()))) {
                    continue;
                }

                String actorName = parsed.getPlayer();
                if (actorName == null || actorName.isBlank() || actorName.length() > 64) {
                    incomplete = true;
                    continue;
                }
                if (!allowedNames.isEmpty()
                    && !allowedNames.contains(actorName.toLowerCase(java.util.Locale.ROOT))) {
                    incomplete = true;
                    continue;
                }
                Material material = parsed.getType();
                String materialName = material == null ? null : material.getKey().toString();
                String worldId = parsed.worldName();
                if (worldId == null || worldId.isBlank()) {
                    worldId = fallbackWorld;
                }
                long timestamp = parsed.getTimestamp();
                if (worldId == null || worldId.isBlank() || materialName == null
                    || timestamp <= 0 || materialName.length() > 128) {
                    incomplete = true;
                    continue;
                }
                records.add(new BlockChange(
                    Instant.ofEpochMilli(timestamp),
                    actorUuid,
                    actorName,
                    action,
                    new Location(worldId, x, y, z, 0, 0),
                    materialName
                ));
            } catch (RuntimeException malformedRow) {
                incomplete = true;
            }
        }
        Integer totalCount = incomplete ? null : records.size();
        String source = actorUuid == null
            ? "CoreProtect API v12+; actor UUID is unavailable in area history"
            : "CoreProtect API v12+; player names verified against UUID history";
        return new CoreProtectHistoryProvider.LookupBatch(records, totalCount, incomplete, source);
    }

    private World resolveLoadedWorld(String worldId) {
        try {
            return server.getWorld(UUID.fromString(worldId));
        } catch (IllegalArgumentException ignored) {
            return server.getWorld(worldId);
        }
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
