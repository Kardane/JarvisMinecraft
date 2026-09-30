package io.github.kardane.jarvisminecraft.paper263;

import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.Capability;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainGateway;
import io.github.kardane.jarvisminecraft.common.brain.EmbeddedBrainSettings;
import io.github.kardane.jarvisminecraft.common.brain.PackagingSmoke;
import io.github.kardane.jarvisminecraft.common.brain.ServerIdentity;
import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
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
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.chat.InteractionCoordinator;
import io.github.kardane.jarvisminecraft.paper.chat.PaperChatListener;
import io.github.kardane.jarvisminecraft.paper.config.PaperJarvisConfigSource;
import io.github.kardane.jarvisminecraft.paper.platform.BukkitPaperPlatformAccess;
import io.github.kardane.jarvisminecraft.paper.platform.PaperPlatformAccess;
import io.github.kardane.jarvisminecraft.paper.platform.PaperServerScheduler;
import io.github.kardane.jarvisminecraft.paper.integrations.IntegrationRegistry;
import io.github.kardane.jarvisminecraft.paper.logging.PaperJarvisLog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class JarvisPaper263Plugin extends JavaPlugin {
    private static final String SUPPORTED_MINECRAFT_VERSION = "26.3";
    private static final String ADAPTER_VERSION = "0.1.0-dev";

    private BrainGateway brain;
    private RuntimeConfigurationManager runtimeConfiguration;
    private RuntimeConfigurationReloadService reloadService;
    private ConfigManager configManager;
    private ChatSessionManager sessions;
    private InteractionCoordinator interactions;
    private PaperPlatformAccess platform;
    private IntegrationRegistry integrations;
    private RuntimeStatistics statistics;
    private Clock clock;
    private JarvisLog operationalLog;
    private ServerScheduler serverScheduler;
    private ToolRegistry registry;
    private CommonRuntime commonRuntime;
    private String serverId;

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

        if (PackagingSmoke.requested()) {
            PackagingSmoke.mark("Paper");
            getLogger().info("E16 Paper clean boot smoke OK");
            Bukkit.shutdown();
            return;
        }

        saveDefaultConfig();

        final RuntimeConfigurationManager loadedRuntimeConfiguration;
        final ConfigManager loadedConfigManager;
        try {
            loadedRuntimeConfiguration =
                new RuntimeConfigurationManager(
                    getDataFolder().toPath(),
                    this::loadRuntimeConfig
                );
            loadedConfigManager =
                loadedRuntimeConfiguration.configManager();
        } catch (RuntimeException failure) {
            getLogger().log(
                Level.SEVERE,
                "JARVIS Paper configuration is invalid. No secret value was logged.",
                failure
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        runtimeConfiguration = loadedRuntimeConfiguration;
        reloadService =
            new RuntimeConfigurationReloadService(
                runtimeConfiguration
            );
        configManager = loadedConfigManager;
        clock = Clock.systemUTC();
        statistics = new RuntimeStatistics(clock.instant());
        operationalLog = new StatisticsJarvisLog(
            statistics,
            new ConfiguredJarvisLog(
                configManager,
                new PaperJarvisLog(getLogger())
            )
        );
        platform = new BukkitPaperPlatformAccess(getServer());
        serverScheduler = new PaperServerScheduler(this);

        try {
            serverId = ServerIdentity.resolve(
                setting("jarvis.serverId", "JARVIS_SERVER_ID", ""),
                getDataFolder().toPath()
            );
            integrations = IntegrationRegistry.create(
                getServer(),
                platform,
                clock,
                getDataFolder().toPath(),
                getLogger()
            );
            registry = integrations.toolRegistry();
            EnumSet<ToolName> policyTools =
                EnumSet.noneOf(ToolName.class);
            policyTools.addAll(registry.registeredTools());
            policyTools.add(ToolName.SCHEDULE_ACTION);
            policyTools.add(ToolName.CANCEL_SCHEDULED_ACTION);
            policyTools.add(ToolName.WEB_SEARCH);
            registry.declarePolicyTools(policyTools);
            commonRuntime = new CommonRuntime(
                registry,
                serverScheduler,
                platform::isOnlineOperator,
                clock
            );
        } catch (RuntimeException failure) {
            getLogger().log(
                Level.SEVERE,
                "JARVIS Tool registry or server identity could not initialize.",
                failure
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        sessions = new ChatSessionManager(clock);
        interactions = new InteractionCoordinator(
            sessions,
            configManager
        );
        var statusCommand = getCommand("jm");
        if (statusCommand != null) {
            statusCommand.setExecutor((sender, command, label, args) -> {
                if (args.length != 1) {
                    sender.sendMessage("/jm <status|stats|reload>");
                    return true;
                }
                if ("status".equalsIgnoreCase(args[0])) {
                    if (brain == null) {
                        sender.sendMessage(
                            "[JARVIS] Brain is inactive. Add Provider credentials to config.yml and run /jm reload."
                        );
                        return true;
                    }
                    JarvisStatusFormatter.styledLines(
                        brain.status()
                    ).forEach(line ->
                        sender.sendMessage(
                            renderStatusLine(line)
                        )
                    );
                    return true;
                }
                if ("stats".equalsIgnoreCase(args[0])) {
                    JarvisStatsFormatter.styledLines(
                        statistics.snapshot()
                    ).forEach(line ->
                        sender.sendMessage(
                            renderStatusLine(line)
                        )
                    );
                    return true;
                }
                if ("reload".equalsIgnoreCase(args[0])) {
                    if (
                        !sender.hasPermission(
                            "jarvisminecraft.reload"
                        )
                    ) {
                        sender.sendMessage(
                            "You do not have permission to reload JARVIS."
                        );
                        return true;
                    }
                    reloadService.reloadAsync()
                        .whenComplete((result, failure) ->
                            serverScheduler.submit(() -> {
                                if (isEnabled()) {
                                    finishReload(
                                        sender,
                                        result,
                                        failure
                                    );
                                }
                                return java.util.concurrent.CompletableFuture.completedFuture(
                                    null
                                );
                            })
                        );
                    return true;
                }
                sender.sendMessage("/jm <status|stats|reload>");
                return true;
            });
        }

        getServer().getScheduler().runTaskTimer(
            this,
            this::sweepSessions,
            20L,
            20L
        );

        startBrain(integrations.capabilities(Bukkit.getMinecraftVersion()));
        getLogger().info(
            "JARVIS runtime policy config validated "
                + "(interaction + proactive + reasoning + response + execution + scheduling policy active; /jm reload active): "
                + JarvisConfigSummary.from(configManager.current()).toLogLine()
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
        interactions = null;
        if (reloadService != null) {
            reloadService.close();
            reloadService = null;
        }
        configManager = null;
        runtimeConfiguration = null;
        statistics = null;
        operationalLog = null;
        serverScheduler = null;
        clock = null;
        registry = null;
        commonRuntime = null;
        serverId = null;
    }

    private boolean startBrain(List<Capability> capabilities) {
        if (brain != null) {
            return true;
        }

        String openAiApiKey = setting(
            "jarvis.openaiApiKey",
            "OPENAI_API_KEY",
            getConfig().getString("openai-api-key", "")
        );
        String typesafeApiKey = setting(
            "jarvis.typesafeApiKey",
            "TYPESAFE_API_KEY",
            getConfig().getString("typesafe-api-key", "")
        );
        if (openAiApiKey.isBlank() || typesafeApiKey.isBlank()) {
            getLogger().warning(
                "JARVIS Brain is inactive because Provider credentials are missing. Add them to config.yml and run /jm reload."
            );
            return false;
        }

        BrainGateway loadedBrain = null;
        try {
            EmbeddedBrainSettings settings = new EmbeddedBrainSettings(
                serverId,
                openAiApiKey,
                typesafeApiKey,
                getDataFolder().toPath().resolve("audit")
            );
            loadedBrain = EmbeddedBrainGateway.live(
                settings.serverId(),
                capabilities,
                settings.openAiApiKey(),
                settings.typesafeApiKey(),
                settings.auditDirectory(),
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
            loadedBrain.start();
            getServer().getPluginManager().registerEvents(
                new PaperChatListener(
                    sessions,
                    interactions,
                    loadedBrain,
                    platform,
                    serverScheduler
                ),
                this
            );
            brain = loadedBrain;
            getLogger().info(
                "JARVIS Paper enabled for serverId="
                    + settings.serverId()
                    + " with Embedded Brain."
            );
            return true;
        } catch (RuntimeException failure) {
            if (loadedBrain != null) {
                loadedBrain.stop();
            }
            getLogger().warning(
                "JARVIS Brain could not start. Check the configuration and run /jm reload. No credential value was logged."
            );
            return false;
        }
    }

    private Component renderStatusLine(
        JarvisStatusFormatter.StatusLine line
    ) {
        return switch (line.kind()) {
            case TITLE -> Component.text(
                    "━━ " + line.label() + " ━━"
                )
                .color(
                    TextColor.color(
                        JarvisStatusFormatter.TITLE_RGB
                    )
                )
                .decorate(TextDecoration.BOLD);
            case SECTION -> Component.text(
                    "  " + line.label()
                )
                .color(
                    TextColor.color(
                        JarvisStatusFormatter.SECTION_RGB
                    )
                )
                .decorate(TextDecoration.BOLD);
            case FIELD -> Component.text("  ")
                .append(
                    Component.text(line.label() + ": ")
                        .color(
                            TextColor.color(
                                JarvisStatusFormatter.KEY_RGB
                            )
                        )
                )
                .append(
                    Component.text(line.value())
                        .color(
                            TextColor.color(
                                line.valueRgb()
                            )
                        )
                );
        };
    }

    private void finishReload(
        CommandSender sender,
        RuntimeConfigurationManager.ReloadResult result,
        Throwable failure
    ) {
        if (
            failure == null
                && result != null
                && result.success()
        ) {
            logReloadSuccess(result);
            if (brain == null) {
                String version = Bukkit.getMinecraftVersion();
                java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> integrations.capabilities(version)
                ).whenComplete((capabilities, capabilitiesFailure) ->
                    serverScheduler.submit(() -> {
                        if (isEnabled()) {
                            boolean running = capabilitiesFailure == null
                                && startBrain(capabilities);
                            sender.sendMessage(running
                                ? "[JARVIS] Configuration reloaded."
                                : "[JARVIS] Configuration reloaded; Brain remains inactive. Check Provider credentials."
                            );
                        }
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    })
                );
            } else {
                sender.sendMessage("[JARVIS] Configuration reloaded.");
            }
            return;
        }

        sender.sendMessage(
            "[JARVIS] Reload failed; previous configuration remains active."
        );
        getLogger().warning(
            "JARVIS configuration reload failed; previous configuration remains active."
        );
    }

    private void logReloadSuccess(
        RuntimeConfigurationManager.ReloadResult result
    ) {
        getLogger().info(
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

    private JarvisConfig loadRuntimeConfig() {
        reloadConfig();
        return JarvisConfigLoader.load(
            new PaperJarvisConfigSource(getConfig())
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

    private void sweepSessions() {
        if (
            sessions == null
                || interactions == null
                || brain == null
                || platform == null
        ) {
            return;
        }

        for (ChatSessionManager.SessionHandle expired : sessions.pruneExpired()) {
            brain.cancelSession(
                expired.requesterUuid(),
                expired.sessionId(),
                CancelReason.SESSION_ENDED
            );
        }

        for (
            UUID revoked
                : interactions.pruneInvalid(platform::interactionPlayer)
        ) {
            brain.cancelActor(revoked, CancelReason.OP_REVOKED);
        }
    }
}
