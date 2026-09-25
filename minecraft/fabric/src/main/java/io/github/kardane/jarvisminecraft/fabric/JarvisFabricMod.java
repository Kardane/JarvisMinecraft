package io.github.kardane.jarvisminecraft.fabric;

import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.transport.JdkBrainWebSocketTransport;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.fabric.chat.FabricChatController;
import io.github.kardane.jarvisminecraft.fabric.platform.FabricPlatformAccess;
import io.github.kardane.jarvisminecraft.fabric.platform.FabricServerScheduler;
import io.github.kardane.jarvisminecraft.fabric.platform.MinecraftFabricPlatformAccess;
import io.github.kardane.jarvisminecraft.fabric.tools.FabricToolService;
import io.github.kardane.jarvisminecraft.fabric.transport.FabricBrainConnection;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

        final FabricAdapterConfig config;
        try {
            config = loadConfig();
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
        ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(
                    runnable,
                    "jarvis-fabric-reconnect"
                );
                thread.setDaemon(true);
                return thread;
            });

        String adapterVersion = modVersion("jarvisminecraft");
        String loaderVersion = modVersion("fabricloader");

        FabricBrainConnection brain = new FabricBrainConnection(
            config.serverId(),
            server.getVersion(),
            adapterVersion,
            loaderVersion,
            clock,
            commonRuntime,
            serverScheduler,
            platform,
            sessions::isActive,
            () -> new JdkBrainWebSocketTransport(
                config.brainUri(),
                config.sharedSecret()
            ),
            reconnect -> reconnectExecutor.schedule(
                reconnect,
                config.reconnectDelayTicks() * 50L,
                TimeUnit.MILLISECONDS
            ),
            LOGGER
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
            chat,
            reconnectExecutor
        );
        runtime = next;
        brain.start();

        LOGGER.info(
            "JARVIS Fabric Adapter enabled for serverId="
                + config.serverId()
                + " on loopback Brain transport."
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

    private FabricAdapterConfig loadConfig() {
        String serverId = setting(
            "jarvis.serverId",
            "JARVIS_SERVER_ID",
            "main"
        );
        String brainUrl = setting(
            "jarvis.brainUrl",
            "JARVIS_BRAIN_URL",
            "ws://127.0.0.1:8181/ws"
        );
        String sharedSecret = setting(
            "jarvis.sharedSecret",
            "JARVIS_SHARED_SECRET",
            ""
        );
        long reconnectDelay = Long.parseLong(
            setting(
                "jarvis.reconnectDelayTicks",
                "JARVIS_RECONNECT_DELAY_TICKS",
                "40"
            )
        );

        return FabricAdapterConfig.validate(
            serverId,
            brainUrl,
            sharedSecret,
            reconnectDelay
        );
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

    private String modVersion(String modId) {
        return FabricLoader.getInstance()
            .getModContainer(modId)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    private record RuntimeState(
        MinecraftServer server,
        FabricBrainConnection brain,
        FabricChatController chat,
        ScheduledExecutorService reconnectExecutor
    ) {
        void close() {
            brain.stop();
            reconnectExecutor.shutdownNow();
        }
    }
}
