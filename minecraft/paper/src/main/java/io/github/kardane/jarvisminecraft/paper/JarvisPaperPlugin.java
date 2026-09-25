package io.github.kardane.jarvisminecraft.paper;

import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.transport.JdkBrainWebSocketTransport;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.paper.chat.PaperChatListener;
import io.github.kardane.jarvisminecraft.paper.platform.BukkitPaperPlatformAccess;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess;
import io.github.kardane.jarvisminecraft.paper.platform.PaperServerScheduler;
import io.github.kardane.jarvisminecraft.paper.integrations.IntegrationRegistry;
import io.github.kardane.jarvisminecraft.paper.transport.PaperBrainConnection;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.util.UUID;
import java.util.logging.Level;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;

public final class JarvisPaperPlugin extends JavaPlugin {
    private static final String SUPPORTED_MINECRAFT_VERSION = "1.21.8";
    private static final String ADAPTER_VERSION = "0.1.0-dev";

    private PaperBrainConnection brain;
    private ChatSessionManager sessions;
    private PaperPlatformAccess platform;
    private IntegrationRegistry integrations;

    @Override
    public void onEnable() {
        if (!SUPPORTED_MINECRAFT_VERSION.equals(Bukkit.getMinecraftVersion())) {
            getLogger().severe(
                "Unsupported Minecraft version. T06 is verified for "
                    + SUPPORTED_MINECRAFT_VERSION
                    + " only."
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();

        final PaperAdapterConfig config;
        try {
            config = PaperAdapterConfig.validate(
                getConfig().getString("server-id", "main"),
                getConfig().getString("brain-url", "ws://127.0.0.1:8181/ws"),
                getConfig().getString("shared-secret", ""),
                getConfig().getLong("reconnect-delay-ticks", 40L)
            );
        } catch (RuntimeException failure) {
            getLogger().log(
                Level.SEVERE,
                "JARVIS Paper configuration is invalid. No secret value was logged.",
                failure
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Clock clock = Clock.systemUTC();
        platform = new BukkitPaperPlatformAccess(getServer());
        ServerScheduler serverScheduler = new PaperServerScheduler(this);

        integrations = IntegrationRegistry.create(getServer(), platform, clock, getLogger());
        ToolRegistry registry = integrations.toolRegistry();

        CommonRuntime commonRuntime = new CommonRuntime(
            registry,
            serverScheduler,
            platform::isOnlineOperator,
            clock
        );

        sessions = new ChatSessionManager(clock);
        brain = new PaperBrainConnection(
            config.serverId(),
            Bukkit.getMinecraftVersion(),
            ADAPTER_VERSION,
            Bukkit.getBukkitVersion(),
            registry.tools(),
            integrations.capabilities(Bukkit.getMinecraftVersion()),
            clock,
            commonRuntime,
            serverScheduler,
            platform,
            sessions::isActive,
            () -> new JdkBrainWebSocketTransport(config.brainUri(), config.sharedSecret()),
            reconnect -> getServer()
                .getScheduler()
                .runTaskLaterAsynchronously(this, reconnect, config.reconnectDelayTicks()),
            getLogger()
        );

        getServer().getPluginManager().registerEvents(
            new PaperChatListener(sessions, brain, platform, serverScheduler),
            this
        );

        getServer().getScheduler().runTaskTimer(
            this,
            this::sweepSessions,
            20L,
            20L
        );

        brain.start();
        getLogger().info(
            "JARVIS Paper Adapter enabled for serverId="
                + config.serverId()
                + " on loopback Brain transport."
        );
    }

    @Override
    public void onDisable() {
        if (brain != null) {
            brain.stop();
            brain = null;
        }
        if (integrations != null) {
            integrations.close();
            integrations = null;
        }
    }

    private void sweepSessions() {
        if (sessions == null || brain == null || platform == null) {
            return;
        }

        for (ChatSessionManager.SessionHandle expired : sessions.pruneExpired()) {
            brain.cancelSession(
                expired.requesterUuid(),
                expired.sessionId(),
                CancelReason.SESSION_ENDED
            );
        }

        for (UUID revoked : sessions.pruneInvalid(platform::isOnlineOperator)) {
            brain.cancelActor(revoked, CancelReason.OP_REVOKED);
        }
    }
}
