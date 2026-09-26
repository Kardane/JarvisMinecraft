package io.github.kardane.jarvisminecraft.paper;

import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainSettings;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.paper.chat.PaperChatListener;
import io.github.kardane.jarvisminecraft.paper.platform.BukkitPaperPlatformAccess;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess;
import io.github.kardane.jarvisminecraft.paper.platform.PaperServerScheduler;
import io.github.kardane.jarvisminecraft.paper.integrations.IntegrationRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.util.UUID;
import java.util.logging.Level;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;

public final class JarvisPaperPlugin extends JavaPlugin {
    private static final String SUPPORTED_MINECRAFT_VERSION = "1.21.8";
    private static final String ADAPTER_VERSION = "0.1.0-dev";

    private BrainGateway brain;
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

        final EmbeddedBrainSettings embeddedSettings;
        try {
            embeddedSettings = EmbeddedBrainSettings.resolve(
                setting(
                    "jarvis.serverId",
                    "JARVIS_SERVER_ID",
                    ""
                ),
                setting(
                    "jarvis.openaiApiKey",
                    "OPENAI_API_KEY",
                    getConfig().getString("openai-api-key", "")
                ),
                setting(
                    "jarvis.typesafeApiKey",
                    "TYPESAFE_API_KEY",
                    getConfig().getString("typesafe-api-key", "")
                ),
                getDataFolder().toPath()
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
        brain = EmbeddedBrainGateway.live(
            embeddedSettings.serverId(),
            integrations.capabilities(Bukkit.getMinecraftVersion()),
            embeddedSettings.openAiApiKey(),
            embeddedSettings.typesafeApiKey(),
            embeddedSettings.auditDirectory(),
            sessions,
            registry,
            commonRuntime,
            serverScheduler,
            platform,
            clock
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
            "JARVIS Paper enabled for serverId="
                + embeddedSettings.serverId()
                + " with Embedded Brain."
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

    private String setting(
        String property,
        String environment,
        String fallback
    ) {
        String propertyValue = System.getProperty(property);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String environmentValue = System.getenv(environment);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }
        return fallback;
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
