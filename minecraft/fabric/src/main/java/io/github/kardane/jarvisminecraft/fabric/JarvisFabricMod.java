package io.github.kardane.jarvisminecraft.fabric;

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
import io.github.kardane.jarvisminecraft.fabric.chat.FabricChatController;
import io.github.kardane.jarvisminecraft.fabric.logging.FabricJarvisLog;
import io.github.kardane.jarvisminecraft.fabric.platform.FabricPlatformAccess;
import io.github.kardane.jarvisminecraft.fabric.platform.FabricServerScheduler;
import io.github.kardane.jarvisminecraft.fabric.platform.MinecraftFabricPlatformAccess;
import io.github.kardane.jarvisminecraft.fabric.tools.FabricToolService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

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
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) ->
                dispatcher.register(
                    CommandManager.literal("jm")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(
                            CommandManager.literal("status")
                                .executes(context -> {
                                    RuntimeState current = runtime;
                                    if (current == null) {
                                        context.getSource().sendError(
                                            Text.literal("JARVIS runtime is not running.")
                                        );
                                        return 0;
                                    }
                                    JarvisStatusFormatter.styledLines(
                                        current.brain().status()
                                    ).forEach(line ->
                                        context.getSource().sendFeedback(
                                            () -> renderStatusLine(line),
                                            false
                                        )
                                    );
                                    return 1;
                                })
                        )
                        .then(
                            CommandManager.literal("stats")
                                .executes(context -> {
                                    RuntimeState current = runtime;
                                    if (current == null) {
                                        context.getSource().sendError(
                                            Text.literal("JARVIS runtime is not running.")
                                        );
                                        return 0;
                                    }
                                    JarvisStatsFormatter.styledLines(
                                        current.statistics().snapshot()
                                    ).forEach(line ->
                                        context.getSource().sendFeedback(
                                            () -> renderStatusLine(line),
                                            false
                                        )
                                    );
                                    return 1;
                                })
                        )
                        .then(
                            CommandManager.literal("reload")
                                .executes(context -> {
                                    RuntimeState current = runtime;
                                    if (current == null) {
                                        context.getSource().sendError(
                                            Text.literal("JARVIS runtime is not running.")
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
                                                    source.sendFeedback(
                                                        () -> Text.literal(
                                                            "[JARVIS] Configuration reloaded."
                                                        ),
                                                        false
                                                    );
                                                    logReloadSuccess(result);
                                                    return;
                                                }
                                                source.sendError(
                                                    Text.literal(
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
                )
        );
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

    private Text renderStatusLine(
        JarvisStatusFormatter.StatusLine line
    ) {
        return switch (line.kind()) {
            case TITLE -> Text.literal(
                    "━━ " + line.label() + " ━━"
                )
                .setStyle(
                    Style.EMPTY
                        .withColor(
                            JarvisStatusFormatter.TITLE_RGB
                        )
                        .withBold(true)
                );
            case SECTION -> Text.literal(
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
                MutableText output = Text.literal("  ");
                output.append(
                    Text.literal(line.label() + ": ")
                        .setStyle(
                            Style.EMPTY.withColor(
                                JarvisStatusFormatter.KEY_RGB
                            )
                        )
                );
                output.append(
                    Text.literal(line.value())
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

        if (PackagingSmoke.requested()) {
            PackagingSmoke.mark("Fabric");
            LOGGER.info("E16 Fabric clean boot smoke OK");
            server.stop(false);
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
                "JARVIS Fabric configuration is invalid. No secret value was logged.",
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
                new FabricJarvisLog(LOGGER)
            )
        );
        FabricPlatformAccess platform =
            new MinecraftFabricPlatformAccess(server);
        ServerScheduler serverScheduler = new FabricServerScheduler(server);

        ToolPolicy toolPolicy = ToolPolicy.inDirectory(dataDirectory);
        ToolRegistry registry = new ToolRegistry(toolPolicy);
        new FabricToolService(
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
                "Fabric",
                server.getVersion()
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

        FabricChatController chat = new FabricChatController(
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
        FabricChatController chat,
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
