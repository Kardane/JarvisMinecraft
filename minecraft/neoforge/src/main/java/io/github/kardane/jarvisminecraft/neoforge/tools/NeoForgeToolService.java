package io.github.kardane.jarvisminecraft.neoforge.tools;

import io.github.kardane.jarvisminecraft.common.runtime.CommandActionPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.tools.StandardMinecraftToolService;
import io.github.kardane.jarvisminecraft.neoforge.platform.NeoForgePlatformAccess;

import java.nio.file.Path;
import java.time.Clock;

public final class NeoForgeToolService {
    public static final String SOURCE = "NeoForge";

    private final StandardMinecraftToolService delegate;

    public NeoForgeToolService(NeoForgePlatformAccess platform, Clock clock) {
        this.delegate = new StandardMinecraftToolService(
            platform,
            clock,
            SOURCE,
            "NeoForgeTickSampler",
            null
        );
    }

    public NeoForgeToolService(
        NeoForgePlatformAccess platform,
        Clock clock,
        Path dataDirectory
    ) {
        this.delegate = new StandardMinecraftToolService(
            platform,
            clock,
            SOURCE,
            "NeoForgeTickSampler",
            null,
            new CommandActionPolicy(
                dataDirectory.resolve("actions.properties")
            )
        );
    }

    public void register(ToolRegistry registry) {
        delegate.register(registry);
    }
}
