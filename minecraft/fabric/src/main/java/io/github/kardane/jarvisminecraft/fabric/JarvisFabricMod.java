package io.github.kardane.jarvisminecraft.fabric;

import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainSettings;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.tools.StandardMinecraftTools;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.fabric.chat.FabricChatController;
import io.github.kardane.jarvisminecraft.fabric.platform.FabricPlatformAccess;
import io.github.kardane.jarvisminecraft.fabric.platform.FabricServerScheduler;
import io.github.kardane.jarvisminecraft.fabric.platform.MinecraftFabricPlatformAccess;
import io.github.kardane.jarvisminecraft.fabric.tools.FabricToolService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.time.Clock;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JarvisFabricMod implements ModInitializer {
    private static final String SUPPORTED_MINECRAFT_VERSION = "1.21.8";
    private static final Logger LOGGER = Logger.getLogger("JarvisMinecraft/Fabric");

    private volatile RuntimeState runtime;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            RuntimeState current = runtime;
            if (current == null) {
                return true;
            }
            return current.chat().allowChat(
                sender,
                message.getContent().getString()
            );
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            RuntimeState current = runtime;
            if (current != null && current.server() == server) {
                current.chat().onDisconnect(handler.getPlayer());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RuntimeState current = runtime;
            if (current != null && current.server() == server) {
                current.chat().sweepSessions();
            }
        });
    }

    private void onServerStarted(MinecraftServer server) {
        if (!SUPPORTED_MINECRAFT_VERSION.equals(server.getVersion())) {
            LOGGER.severe(
                "Unsupported Minecraft version. T07 is verified for "
                    + SUPPORTED_MINECRAFT_VERSION
                    + " only; detected "
                    + server.getVersion()
                    + "."
            );
            return;
        }

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
                    ""
                ),
                setting(
                    "jarvis.typesafeApiKey",
                    "TYPESAFE_API_KEY",
                    ""
                ),
                Path.of(
                    "config",
                    "jarvisminecraft"
                )
            );
        } catch (RuntimeException failure) {
            LOGGER.log(
                Level.SEVERE,
                "JARVIS Fabric configuration is invalid. No secret value was logged.",
                failure
            );
            return;
        }

        Clock clock = Clock.systemUTC();
        FabricPlatformAccess platform =
            new MinecraftFabricPlatformAccess(server);
        ServerScheduler serverScheduler = new FabricServerScheduler(server);

        ToolRegistry registry = new ToolRegistry();
        new FabricToolService(platform, clock).register(registry);

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
                "Fabric",
                server.getVersion()
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

        FabricChatController chat = new FabricChatController(
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
            chat
        );
        runtime = next;
        brain.start();

        LOGGER.info(
            "JARVIS Fabric enabled for serverId="
                + embeddedSettings.serverId()
                + " with Embedded Brain."
        );
    }

    private void onServerStopping(MinecraftServer server) {
        RuntimeState current = runtime;
        if (current == null || current.server() != server) {
            return;
        }
        runtime = null;
        current.close();
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
        FabricChatController chat
    ) {
        void close() {
            brain.stop();
        }
    }
}
