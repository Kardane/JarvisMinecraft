package io.github.kardane.jarvisminecraft.paper.integrations.coreprotect;

import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import org.bukkit.Server;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Loaded reflectively only when CoreProtect is installed and enabled. */
public final class CoreProtectIntegrationModule {
    private CoreProtectIntegrationModule() {
    }

    public static AutoCloseable install(ToolRegistry registry, Server server, Clock clock) {
        Optional<CoreProtectPaperAccess> access =
            CoreProtectPaperAccess.discover(server.getPluginManager(), server);
        if (access.isEmpty()) {
            return null;
        }
        CoreProtectHistoryProvider provider = CoreProtectHistoryProvider.create(
            access.get(),
            clock,
            failure -> server.getLogger().warning(
                "[JARVIS] coreprotect.query.failed operation=" + failure.operation()
                    + " exception_type=" + failure.exceptionType()
                    + " root_cause_type=" + Objects.toString(failure.rootCauseType(), "none")
            )
        );
        provider.registerTools(registry);
        return provider;
    }
}
