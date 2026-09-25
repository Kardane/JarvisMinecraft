package io.github.kardane.jarvisminecraft.paper.integrations.cmi;

import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import org.bukkit.Server;

import java.time.Clock;
import java.util.Optional;

/** Loaded reflectively only when CMI and CMILib are installed and enabled. */
public final class CmiIntegrationModule {
    private CmiIntegrationModule() {
    }

    public static AutoCloseable install(ToolRegistry registry, Server server, Clock clock) {
        Optional<CmiApiAccess> access = CmiApiAccess.discover(server);
        if (access.isEmpty()) {
            return null;
        }
        CmiPlayerInfoProvider provider = new CmiPlayerInfoProvider(access.get(), clock);
        provider.registerTools(registry);
        return provider;
    }
}
