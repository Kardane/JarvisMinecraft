package io.github.kardane.jarvisminecraft.neoforge;

import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainSettings;
import io.github.kardane.jarvisminecraft.common.brain.PackagingSmoke;
import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfigLoader;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfigSummary;
import io.github.kardane.jarvisminecraft.common.config.RuntimeConfigurationManager;
import io.github.kardane.jarvisminecraft.common.config.RuntimeConfigurationReloadService;
import io.github.kardane.jarvisminecraft.common.logging.ConfiguredJarvisLog;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.logging.JarvisStatsFormatter;
import io.github.kardane.jarvisminecraft.common.logging.JarvisStatusFormatter;
import io.github.kardane.jarvisminecraft.common.logging.RuntimeStatistics;
import io.github.kardane.jarvisminecraft.common.logging.StatisticsJarvisLog;
import io.github.kardane.jarvisminecraft.common.config.PropertiesJarvisConfigSource;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.runtime.ToolReferenceWriter;
import io.github.kardane.jarvisminecraft.common.tools.StandardMinecraftTools;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.chat.InteractionCoordinator;
import io.github.kardane.jarvisminecraft.neoforge.chat.NeoForgeChatController;
import io.github.kardane.jarvisminecraft.neoforge.logging.NeoForgeJarvisLog;
import io.github.kardane.jarvisminecraft.neoforge.platform.MinecraftNeoForgePlatformAccess;
import io.github.kardane.jarvisminecraft.neoforge.platform.NeoForgePlatformAccess;
import io.github.kardane.jarvisminecraft.neoforge.platform.NeoForgeServerScheduler;
import io.github.kardane.jarvisminecraft.neoforge.platform.NeoForgeTickSampler;
import io.github.kardane.jarvisminecraft.neoforge.tools.NeoForgeToolService;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
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
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onChat);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("jm")
                .requires(source -> source.hasPermission(2))
                .then(
                    Commands.literal("status")
                        .executes(context -> {
                            RuntimeState current = runtime;
                            if (current == null) {
                                context.getSource().sendFailure(
                                    Component.literal("JARVIS runtime is not running.")
                                );
                                return 0;
                            }
                            JarvisStatusFormatter.styledLines(
                                current.brain().status()
                            ).forEach(line ->
                                context.getSource().sendSuccess(
                                    () -> renderStatusLine(line),
                                    false
                                )
                            );
                            return 1;
                        })
                )
                .then(
                    Commands.literal("stats")
                        .executes(context -> {
                            RuntimeState current = runtime;
                            if (current == null) {
                                context.getSource().sendFailure(
                                    Component.literal("JARVIS runtime is not running.")
                                );
                                return 0;
                            }
                            JarvisStatsFormatter.styledLines(
                                current.statistics().snapshot()
                            ).forEach(line ->
                                context.getSource().sendSuccess(
                                    () -> renderStatusLine(line),
                                    false
                                )
                            );
                            return 1;
                        })
                )
                .then(
                    Commands.literal("reload")
                        .executes(context -> {
                            RuntimeState current = runtime;
                            if (current == null) {
                                context.getSource().sendFailure(
                                    Component.literal("JARVIS runtime is not running.")
                                );
                                return 0;
                            }
                            var source = context.getSource();
                            current.reloadService()
                                .reloadAsync()
                                .whenComplete((result, failure) ->
                                    current.server().execute(() -> {
                                        if (runtime != current) {
                                            return;
                                        }
                                        if (
                                            failure == null
                                                && result != null
                                                && result.success()
                                        ) {
                                            source.sendSuccess(
                                                () -> Component.literal(
                                                    "[JARVIS] Configuration reloaded."
                                                ),
                                                false
                                            );
                                            logReloadSuccess(result);
                                            return;
                                        }
                                        source.sendFailure(
                                            Component.literal(
                                                "[JARVIS] Reload failed; previous configuration remains active."
                                            )
                                        );
                                        LOGGER.warning(
                                            "JARVIS configuration reload failed; previous configuration remains active."
                                        );
                                    })
                                );
                            return 1;
                        })
                )
        );
    }

    private Component renderStatusLine(
        JarvisStatusFormatter.StatusLine line
    ) {
        return switch (line.kind()) {
            case TITLE -> Component.literal(
                    "━━ " + line.label() + " ━━"
                )
                .setStyle(
                    Style.EMPTY
                        .withColor(
                            JarvisStatusFormatter.TITLE_RGB
                        )
                        .withBold(true)
                );
            case SECTION -> Component.literal(
                    "  " + line.label()
                )
                .setStyle(
                    Style.EMPTY
                        .withColor(
                            JarvisStatusFormatter.SECTION_RGB
                        )
                        .withBold(true)
                );
            case FIELD -> {
                MutableComponent output =
                    Component.literal("  ");
                output.append(
                    Component.literal(
                        line.label() + ": "
                    ).setStyle(
                        Style.EMPTY.withColor(
                            JarvisStatusFormatter.KEY_RGB
                        )
                    )
                );
                output.append(
                    Component.literal(line.value())
                        .setStyle(
                            Style.EMPTY.withColor(
                                line.valueRgb()
                            )
                        )
                );
                yield output;
            }
        };
    }

    private void logReloadSuccess(
        RuntimeConfigurationManager.ReloadResult result
    ) {
        LOGGER.info(
            "JARVIS configuration reloaded: "
                + result.activeConfig().toLogLine()
                + ", personaPresent="
                + result.activeContent().personaPresent()
                + ", personaBytes="
                + result.activeContent().personaBytes()
                + ", knowledgeDocuments="
                + result.activeContent().knowledgeDocuments()
                + ", knowledgeBytes="
                + result.activeContent().knowledgeBytes()
        );
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

        if (PackagingSmoke.requested()) {
            PackagingSmoke.mark("NeoForge");
            LOGGER.info("E16 NeoForge clean boot smoke OK");
            server.halt(false);
            return;
        }

        if (Boolean.getBoolean("jarvis.t08BootSmoke")) {
            writeBootSmokeMarker();
            LOGGER.info("T08 dedicated server boot smoke OK");
            server.halt(false);
            return;
        }

        Path dataDirectory = Path.of("config", "jarvisminecraft");
        Path runtimeConfigPath = dataDirectory.resolve("jarvis.properties");

        final RuntimeConfigurationManager runtimeConfiguration;
        final ConfigManager configManager;
        final EmbeddedBrainSettings embeddedSettings;
        try {
            runtimeConfiguration =
                new RuntimeConfigurationManager(
                    dataDirectory,
                    () -> JarvisConfigLoader.load(
                        PropertiesJarvisConfigSource.load(
                            runtimeConfigPath
                        )
                    )
                );
            configManager =
                runtimeConfiguration.configManager();
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
                dataDirectory
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
        RuntimeStatistics statistics =
            new RuntimeStatistics(clock.instant());
        JarvisLog operationalLog = new StatisticsJarvisLog(
            statistics,
            new ConfiguredJarvisLog(
                configManager,
                new NeoForgeJarvisLog(LOGGER)
            )
        );
        NeoForgeTickSampler tickSampler = new NeoForgeTickSampler();
        NeoForgePlatformAccess platform =
            new MinecraftNeoForgePlatformAccess(server, tickSampler);
        ServerScheduler serverScheduler = new NeoForgeServerScheduler(server);

        ToolPolicy toolPolicy = ToolPolicy.inDirectory(dataDirectory);
        ToolRegistry registry = new ToolRegistry(toolPolicy);
        new NeoForgeToolService(
            platform,
            clock,
            toolPolicy
        ).register(registry);
        ToolReferenceWriter.writeAsync(
            dataDirectory,
            registry.registeredTools()
        ).exceptionally(failure -> {
            LOGGER.warning(
                "Could not write generated JARVIS Tool reference."
            );
            return null;
        });

        CommonRuntime commonRuntime = new CommonRuntime(
            registry,
            serverScheduler,
            platform::isOnlineOperator,
            clock
        );

        ChatSessionManager sessions = new ChatSessionManager(clock);
        InteractionCoordinator interactions =
            new InteractionCoordinator(sessions, configManager);

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
            interactions,
            runtimeConfiguration,
            registry,
            commonRuntime,
            serverScheduler,
            platform,
            clock,
            operationalLog
        );

        NeoForgeChatController chat = new NeoForgeChatController(
            server,
            sessions,
            interactions,
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
            tickSampler,
            runtimeConfiguration,
            statistics,
            new RuntimeConfigurationReloadService(
                runtimeConfiguration
            ),
            configManager
        );
        runtime = next;

        brain.start();
        LOGGER.info(
            "JARVIS runtime policy config validated "
                + "(interaction + proactive + reasoning + response + execution + scheduling policy active; /jm reload active): "
                + JarvisConfigSummary.from(configManager.current()).toLogLine()
        );
        LOGGER.info(
            "JARVIS NeoForge enabled for serverId="
                + embeddedSettings.serverId()
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
        NeoForgeTickSampler tickSampler,
        RuntimeConfigurationManager runtimeConfiguration,
        RuntimeStatistics statistics,
        RuntimeConfigurationReloadService reloadService,
        ConfigManager configManager
    ) {
        void close() {
            reloadService.close();
            brain.stop();
        }
    }
}
