package io.github.kardane.jarvisminecraft.paper.integrations.worldguard;

import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import org.bukkit.Server;

import java.time.Clock;
import java.util.Optional;

/** Loaded reflectively only when WorldGuard and WorldEdit are installed and enabled. */
public final class WorldGuardIntegrationModule {
    private WorldGuardIntegrationModule() {
    }

    public static AutoCloseable install(ToolRegistry registry, Server server, Clock clock) {
        Optional<WorldGuardPaperAccess> access = WorldGuardPaperAccess.discover(server);
        if (access.isEmpty()) {
            return null;
        }
        WorldGuardRegionProvider provider = new WorldGuardRegionProvider(access.get(), clock);
        provider.registerTools(registry);
        return () -> { };
    }
}
