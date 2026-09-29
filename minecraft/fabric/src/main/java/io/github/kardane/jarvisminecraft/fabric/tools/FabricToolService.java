package io.github.kardane.jarvisminecraft.fabric.tools;

import io.github.kardane.jarvisminecraft.common.runtime.ToolPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.tools.StandardMinecraftToolService;
import io.github.kardane.jarvisminecraft.fabric.platform.FabricPlatformAccess;

import java.nio.file.Path;
import java.time.Clock;

public final class FabricToolService {
    public static final String SOURCE = "Fabric";

    private final StandardMinecraftToolService delegate;

    public FabricToolService(FabricPlatformAccess platform, Clock clock) {
        this.delegate = new StandardMinecraftToolService(
            platform,
            clock,
            SOURCE,
            "FabricTickTimes",
            null
        );
    }

    public FabricToolService(
        FabricPlatformAccess platform,
        Clock clock,
        Path dataDirectory
    ) {
        this(
            platform,
            clock,
            ToolPolicy.inDirectory(dataDirectory)
        );
    }

    public FabricToolService(
        FabricPlatformAccess platform,
        Clock clock,
        ToolPolicy toolPolicy
    ) {
        this.delegate = new StandardMinecraftToolService(
            platform,
            clock,
            SOURCE,
            "FabricTickTimes",
            null,
            toolPolicy
        );
    }

    public void register(ToolRegistry registry) {
        delegate.register(registry);
    }
}
