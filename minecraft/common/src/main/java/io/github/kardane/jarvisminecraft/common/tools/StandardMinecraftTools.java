package io.github.kardane.jarvisminecraft.common.tools;

import io.github.kardane.jarvisminecraft.common.brain.Capability;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class StandardMinecraftTools {
    public static final Set<ToolName> TOOLS = Set.copyOf(
        EnumSet.of(
            ToolName.GET_SERVER_STATUS,
            ToolName.GET_ONLINE_PLAYERS,
            ToolName.GET_PLAYER,
            ToolName.GET_PLAYER_LOCATION,
            ToolName.GET_NEARBY_PLAYERS,
            ToolName.GET_WORLD_INFO,
            ToolName.TELEPORT_STAFF
        )
    );

    private StandardMinecraftTools() {
    }

    public static List<Capability> capabilities(
        String source,
        String version
    ) {
        return List.of(
            capability("server.status", source, version),
            capability("player.list", source, version),
            capability("player.lookup", source, version),
            capability("player.location", source, version),
            capability("player.nearby", source, version),
            capability("world.info", source, version),
            capability("staff.self_teleport", source, version)
        );
    }

    private static Capability capability(
        String name,
        String source,
        String version
    ) {
        return new Capability(name, source, version);
    }
}
