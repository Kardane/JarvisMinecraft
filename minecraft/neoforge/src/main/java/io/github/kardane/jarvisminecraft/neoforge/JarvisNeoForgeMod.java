package io.github.kardane.jarvisminecraft.neoforge;

import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainSettings;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.tools.StandardMinecraftTools;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.neoforge.chat.NeoForgeChatController;
import io.github.kardane.jarvisminecraft.neoforge.platform.MinecraftNeoForgePlatformAccess;
import io.github.kardane.jarvisminecraft.neoforge.platform.NeoForgePlatformAccess;
import io.github.kardane.jarvisminecraft.neoforge.platform.NeoForgeServerScheduler;
import io.github.kardane.jarvisminecraft.neoforge.platform.NeoForgeTickSampler;
import io.github.kardane.jarvisminecraft.neoforge.tools.NeoForgeToolService;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.logging.Level;
import java.util.logging.Logger;

@Mod(value = JarvisNeoForgeMod.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class JarvisNeoForgeMod {
    public static final String MOD_ID = "jarvisminecraft";

    private static final String SUPPORTED_MINECRAFT_VERSION = "1.21.8";
    private static final String ADAPTER_VERSION = "0.1.0-dev";
    private static final String NEOFORGE_VERSION = "21.8.52";
    private static final Logger LOGGER = Logger.getLogger("JarvisMinecraft/NeoForge");

    private volatile RuntimeState runtime;

    public JarvisNeoForgeMod() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onChat);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
    }

    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        String minecraftVersion = SharedConstants.getCurrentVersion().name();

        if (!SUPPORTED_MINECRAFT_VERSION.equals(minecraftVersion)) {
            LOGGER.severe(
                "Unsupported Minecraft version. T08 is verified for "
                    + SUPPORTED_MINECRAFT_VERSION
                    + " only; detected "
                    + minecraftVersion
                    + "."
            );
            return;
        }

        final String serverId;
        final EmbeddedBrainSettings embeddedSettings;
        try {
            serverId = setting(
                "jarvis.serverId",
                "JARVIS_SERVER_ID",
                "main"
            );
            embeddedSettings = new EmbeddedBrainSettings(
                serverId,
                setting(
                    "jarvis.openaiApiKey",
                    "OPENAI_API_KEY",
                    ""
                ),
                setting(
                    "jarvis.typesafeApiKey",
                    "TYPESAFE_API_KEY",
                    ""
                ),
                Path.of(
                    "config",
                    "jarvisminecraft",
                    "audit"
                )
            );
        } catch (RuntimeException failure) {
            LOGGER.log(
                Level.SEVERE,
                "JARVIS NeoForge configuration is invalid. No secret value was logged.",
                failure
            );
            return;
        }

        Clock clock = Clock.systemUTC();
        NeoForgeTickSampler tickSampler = new NeoForgeTickSampler();
        NeoForgePlatformAccess platform =
            new MinecraftNeoForgePlatformAccess(server, tickSampler);
        ServerScheduler serverScheduler = new NeoForgeServerScheduler(server);

        ToolRegistry registry = new ToolRegistry();
        new NeoForgeToolService(platform, clock).register(registry);

        CommonRuntime commonRuntime = new CommonRuntime(
            registry,
            serverScheduler,
            platform::isOnlineOperator,
            clock
        );

        ChatSessionManager sessions = new ChatSessionManager(clock);

        BrainGateway brain = EmbeddedBrainGateway.live(
            embeddedSettings.serverId(),
            StandardMinecraftTools.capabilities(
                "NeoForge",
                minecraftVersion
            ),
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

        NeoForgeChatController chat = new NeoForgeChatController(
            server,
            sessions,
            brain,
            platform,
            serverScheduler,
            LOGGER
        );

        RuntimeState previous = runtime;
        if (previous != null) {
            previous.close();
        }

        RuntimeState next = new RuntimeState(
            server,
            brain,
            chat,
            tickSampler
        );
        runtime = next;

        if (Boolean.getBoolean("jarvis.t08BootSmoke")) {
            writeBootSmokeMarker();
            LOGGER.info("T08 dedicated server boot smoke OK");
            server.halt(false);
            return;
        }

        brain.start();
        LOGGER.info(
            "JARVIS NeoForge enabled for serverId="
                + serverId
                + " with Embedded Brain."
        );
    }

    private void onServerStopping(ServerStoppingEvent event) {
        RuntimeState current = runtime;
        if (current == null || current.server() != event.getServer()) {
            return;
        }
        runtime = null;
        current.close();
    }

    private void onChat(ServerChatEvent event) {
        RuntimeState current = runtime;
        if (current != null) {
            current.chat().onChat(event);
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        RuntimeState current = runtime;
        if (current == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.getServer() == current.server()) {
            current.chat().onDisconnect(player);
        }
    }

    private void onServerTickPre(ServerTickEvent.Pre event) {
        RuntimeState current = runtime;
        if (current != null && current.server() == event.getServer()) {
            current.tickSampler().beginTick(System.nanoTime());
        }
    }

    private void onServerTickPost(ServerTickEvent.Post event) {
        RuntimeState current = runtime;
        if (current != null && current.server() == event.getServer()) {
            current.tickSampler().endTick(System.nanoTime());
            current.chat().onServerTick();
        }
    }

    private void writeBootSmokeMarker() {
        String marker = System.getProperty("jarvis.t08BootMarker");
        if (marker == null || marker.isBlank()) {
            throw new IllegalStateException("T08 boot smoke marker path is missing.");
        }
        try {
            Path path = Path.of(marker);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                path,
                "T08 dedicated server boot smoke OK\n",
                StandardCharsets.UTF_8
            );
        } catch (Exception failure) {
            throw new IllegalStateException("Could not write T08 boot smoke marker.", failure);
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

    private record RuntimeState(
        MinecraftServer server,
        BrainGateway brain,
        NeoForgeChatController chat,
        NeoForgeTickSampler tickSampler
    ) {
        void close() {
            brain.stop();
        }
    }
}
