package io.github.kardane.jarvisminecraft.common.audit;

import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.*;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AuditArgumentSummaries {
    private AuditArgumentSummaries() {
    }

    public static Map<String, Object> summarize(ToolArguments arguments) {
        if (arguments instanceof NoArguments) {
            return Map.of();
        }
        if (arguments instanceof PagingArguments value) {
            return map("cursor", value.cursor(), "limit", value.limit());
        }
        if (arguments instanceof GetPlayerByUuidArguments value) {
            return map("playerUuid", value.playerUuid());
        }
        if (arguments instanceof GetPlayerByNameArguments value) {
            return map("exactName", value.exactName());
        }
        if (arguments instanceof PlayerUuidArguments value) {
            return map("playerUuid", value.playerUuid());
        }
        if (arguments instanceof NearbyArguments value) {
            return map(
                "center", location(value.center()),
                "radius", value.radius(),
                "limit", value.limit()
            );
        }
        if (arguments instanceof WorldInfoArguments value) {
            return map("worldId", value.worldId());
        }
        if (arguments instanceof TeleportArguments value) {
            return map("targetPlayerUuid", value.targetPlayerUuid());
        }
        if (arguments instanceof AreaHistoryArguments value) {
            return map(
                "center", location(value.center()),
                "radius", value.radius(),
                "lookbackSeconds", value.lookbackSeconds(),
                "cursor", value.cursor(),
                "limit", value.limit()
            );
        }
        if (arguments instanceof PlayerHistoryArguments value) {
            return map(
                "playerUuid", value.playerUuid(),
                "lookbackSeconds", value.lookbackSeconds(),
                "cursor", value.cursor(),
                "limit", value.limit()
            );
        }
        if (arguments instanceof RegionsAtLocationArguments value) {
            return map("location", location(value.location()));
        }
        if (arguments instanceof RegionInfoArguments value) {
            return map(
                "worldId", value.worldId(),
                "regionId", value.regionId()
            );
        }
        if (arguments instanceof BuildPermissionArguments value) {
            return map(
                "playerUuid", value.playerUuid(),
                "location", location(value.location())
            );
        }
        throw new IllegalArgumentException(
            "Unsupported ToolArguments type: " + arguments.getClass().getName()
        );
    }

    private static Map<String, Object> location(Location value) {
        return map(
            "worldId", value.worldId(),
            "x", value.x(),
            "y", value.y(),
            "z", value.z(),
            "yaw", value.yaw(),
            "pitch", value.pitch()
        );
    }

    private static Map<String, Object> map(Object... pairs) {
        LinkedHashMap<String, Object> output = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            if (value != null) {
                output.put((String) pairs[index], value);
            }
        }
        return Map.copyOf(output);
    }
}
