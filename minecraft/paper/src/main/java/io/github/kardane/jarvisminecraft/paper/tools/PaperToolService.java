package io.github.kardane.jarvisminecraft.paper.tools;

import io.github.kardane.jarvisminecraft.common.runtime.ToolPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.tools.StandardMinecraftToolService;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess;

import java.nio.file.Path;
import java.time.Clock;

public final class PaperToolService {
    public static final String SOURCE = "Paper";

    private final StandardMinecraftToolService delegate;

    public PaperToolService(PaperPlatformAccess platform, Clock clock) {
        this.delegate = new StandardMinecraftToolService(
            platform,
            clock,
            SOURCE,
            SOURCE,
            60_000L
        );
    }

    public PaperToolService(
        PaperPlatformAccess platform,
        Clock clock,
        Path dataDirectory
    ) {
        this(
            platform,
            clock,
            ToolPolicy.inDirectory(dataDirectory)
        );
    }

    public PaperToolService(
        PaperPlatformAccess platform,
        Clock clock,
        ToolPolicy toolPolicy
    ) {
        this.delegate = new StandardMinecraftToolService(
            platform,
            clock,
            SOURCE,
            SOURCE,
            60_000L,
            toolPolicy
        );
    }

    public void register(ToolRegistry registry) {
        delegate.register(registry);
    }
}
