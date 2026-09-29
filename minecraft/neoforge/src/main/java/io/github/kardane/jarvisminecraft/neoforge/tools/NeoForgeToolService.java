package io.github.kardane.jarvisminecraft.neoforge.tools;

import io.github.kardane.jarvisminecraft.common.runtime.ToolPolicy;
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
        this(
            platform,
            clock,
            ToolPolicy.inDirectory(dataDirectory)
        );
    }

    public NeoForgeToolService(
        NeoForgePlatformAccess platform,
        Clock clock,
        ToolPolicy toolPolicy
    ) {
        this.delegate = new StandardMinecraftToolService(
            platform,
            clock,
            SOURCE,
            "NeoForgeTickSampler",
            null,
            toolPolicy
        );
    }

    public void register(ToolRegistry registry) {
        delegate.register(registry);
    }
}
