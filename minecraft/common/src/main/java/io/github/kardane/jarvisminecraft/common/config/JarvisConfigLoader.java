package io.github.kardane.jarvisminecraft.common.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class JarvisConfigLoader {
    private JarvisConfigLoader() {
    }

    public static JarvisConfig load(JarvisConfigSource source) {
        Objects.requireNonNull(source, "source");
        JarvisConfig defaults = JarvisConfig.defaults();

        JarvisConfig.Interaction defaultInteraction = defaults.interaction();
        JarvisConfig.Audience defaultAudience = defaultInteraction.audience();
        JarvisConfig.Proactive defaultProactive = defaultInteraction.proactive();
        JarvisConfig.Model defaultModel = defaults.model();
        JarvisConfig.Reasoning defaultReasoning = defaultModel.reasoning();
        JarvisConfig.Personality defaultPersonality = defaults.personality();
        JarvisConfig.Knowledge defaultKnowledge = defaults.knowledge();
        JarvisConfig.Response defaultResponse = defaults.response();
        JarvisConfig.WaitingMessage defaultWaiting =
            defaultResponse.waitingMessage();
        JarvisConfig.Sound defaultSound = defaultResponse.sound();
        JarvisConfig.ResponseMetrics defaultMetrics =
            defaultResponse.metrics();
        JarvisConfig.ConversationArchive defaultConversationArchive =
            defaults.conversationArchive();
        JarvisConfig.ConversationMemory defaultConversationMemory =
            defaults.conversationMemory();
        JarvisConfig.Execution defaultExecution = defaults.execution();
        JarvisConfig.Scheduling defaultScheduling = defaults.scheduling();
        JarvisConfig.Logging defaultLogging = defaults.logging();

        JarvisConfig.Interaction interaction = new JarvisConfig.Interaction(
            enumValue(
                source,
                "jarvis.interaction.mode",
                JarvisConfig.InteractionMode.class,
                defaultInteraction.mode()
            ),
            list(
                source,
                "jarvis.interaction.wake-words",
                defaultInteraction.wakeWords()
            ),
            new JarvisConfig.WakeWordMatching(
                bool(
                    source,
                    "jarvis.interaction.wake-word.anywhere",
                    defaultInteraction.wakeWordMatching().anywhere()
                ),
                bool(
                    source,
                    "jarvis.interaction.wake-word.fuzzy-enabled",
                    defaultInteraction.wakeWordMatching().fuzzyEnabled()
                ),
                integer(
                    source,
                    "jarvis.interaction.wake-word.max-edit-distance",
                    defaultInteraction.wakeWordMatching().maxEditDistance()
                )
            ),
            integer(
                source,
                "jarvis.interaction.follow-up-seconds",
                defaultInteraction.followUpSeconds()
            ),
            decimal(
                source,
                "jarvis.interaction.follow-up-confidence-threshold",
                defaultInteraction.followUpConfidenceThreshold()
            ),
            new JarvisConfig.Audience(
                enumValue(
                    source,
                    "jarvis.interaction.audience.mode",
                    JarvisConfig.AudienceMode.class,
                    defaultAudience.mode()
                ),
                list(
                    source,
                    "jarvis.interaction.audience.whitelist",
                    defaultAudience.whitelist()
                ),
                list(
                    source,
                    "jarvis.interaction.audience.blacklist",
                    defaultAudience.blacklist()
                )
            ),
            new JarvisConfig.Proactive(
                integer(
                    source,
                    "jarvis.interaction.proactive.context-messages",
                    defaultProactive.contextMessages()
                ),
                integer(
                    source,
                    "jarvis.interaction.proactive.cooldown-seconds",
                    defaultProactive.cooldownSeconds()
                ),
                decimal(
                    source,
                    "jarvis.interaction.proactive.confidence-threshold",
                    defaultProactive.confidenceThreshold()
                )
            )
        );

        JarvisConfig.Model model = new JarvisConfig.Model(
            string(source, "jarvis.model.name", defaultModel.name()),
            new JarvisConfig.Reasoning(
                enumValue(
                    source,
                    "jarvis.model.reasoning.mode",
                    JarvisConfig.ReasoningMode.class,
                    defaultReasoning.mode()
                ),
                enumValue(
                    source,
                    "jarvis.model.reasoning.fallback",
                    JarvisConfig.ReasoningMode.class,
                    defaultReasoning.fallback()
                )
            )
        );

        JarvisConfig.Personality personality = new JarvisConfig.Personality(
            bool(
                source,
                "jarvis.personality.enabled",
                defaultPersonality.enabled()
            )
        );

        JarvisConfig.Knowledge knowledge = new JarvisConfig.Knowledge(
            bool(
                source,
                "jarvis.knowledge.enabled",
                defaultKnowledge.enabled()
            ),
            integer(
                source,
                "jarvis.knowledge.max-files",
                defaultKnowledge.maxFiles()
            ),
            integer(
                source,
                "jarvis.knowledge.max-file-bytes",
                defaultKnowledge.maxFileBytes()
            ),
            integer(
                source,
                "jarvis.knowledge.max-total-bytes",
                defaultKnowledge.maxTotalBytes()
            )
        );

        JarvisConfig.Response response = new JarvisConfig.Response(
            string(source, "jarvis.response.prefix", defaultResponse.prefix()),
            new JarvisConfig.WaitingMessage(
                bool(
                    source,
                    "jarvis.response.waiting-message.enabled",
                    defaultWaiting.enabled()
                ),
                integer(
                    source,
                    "jarvis.response.waiting-message.threshold-ms",
                    defaultWaiting.thresholdMillis()
                ),
                list(
                    source,
                    "jarvis.response.waiting-message.messages",
                    defaultWaiting.messages()
                )
            ),
            new JarvisConfig.Sound(
                bool(
                    source,
                    "jarvis.response.sound.enabled",
                    defaultSound.enabled()
                ),
                string(
                    source,
                    "jarvis.response.sound.id",
                    defaultSound.id()
                ),
                decimal(
                    source,
                    "jarvis.response.sound.volume",
                    defaultSound.volume()
                ),
                decimal(
                    source,
                    "jarvis.response.sound.pitch",
                    defaultSound.pitch()
                )
            ),
            new JarvisConfig.ResponseMetrics(
                bool(
                    source,
                    "jarvis.response.metrics.enabled",
                    defaultMetrics.enabled()
                ),
                string(
                    source,
                    "jarvis.response.metrics.icon",
                    defaultMetrics.icon()
                )
            )
        );

        JarvisConfig.ConversationArchive conversationArchive =
            new JarvisConfig.ConversationArchive(
                bool(
                    source,
                    "jarvis.conversation-archive.enabled",
                    defaultConversationArchive.enabled()
                ),
                integer(
                    source,
                    "jarvis.conversation-archive.max-file-bytes",
                    defaultConversationArchive.maxFileBytes()
                ),
                integer(
                    source,
                    "jarvis.conversation-archive.max-files",
                    defaultConversationArchive.maxFiles()
                )
            );

        JarvisConfig.ConversationMemory conversationMemory =
            new JarvisConfig.ConversationMemory(
                bool(
                    source,
                    "jarvis.conversation-memory.enabled",
                    defaultConversationMemory.enabled()
                ),
                integer(
                    source,
                    "jarvis.conversation-memory.lookback-days",
                    defaultConversationMemory.lookbackDays()
                ),
                integer(
                    source,
                    "jarvis.conversation-memory.max-source-files",
                    defaultConversationMemory.maxSourceFiles()
                ),
                integer(
                    source,
                    "jarvis.conversation-memory.max-turns",
                    defaultConversationMemory.maxTurns()
                ),
                integer(
                    source,
                    "jarvis.conversation-memory.max-context-bytes",
                    defaultConversationMemory.maxContextBytes()
                )
            );

        JarvisConfig.Execution execution = new JarvisConfig.Execution(
            enumValue(
                source,
                "jarvis.execution.mode",
                JarvisConfig.ExecutionMode.class,
                defaultExecution.mode()
            ),
            enumValue(
                source,
                "jarvis.execution.actors",
                JarvisConfig.ExecutionActors.class,
                defaultExecution.actors()
            ),
            new JarvisConfig.ToolFilter(
                list(
                    source,
                    "jarvis.execution.lite.allow-tools",
                    defaultExecution.lite().allowTools()
                ),
                list(
                    source,
                    "jarvis.execution.lite.deny-tools",
                    defaultExecution.lite().denyTools()
                )
            ),
            new JarvisConfig.ToolFilter(
                list(
                    source,
                    "jarvis.execution.full.allow-tools",
                    defaultExecution.full().allowTools()
                ),
                list(
                    source,
                    "jarvis.execution.full.deny-tools",
                    defaultExecution.full().denyTools()
                )
            )
        );

        JarvisConfig.Scheduling scheduling = new JarvisConfig.Scheduling(
            bool(
                source,
                "jarvis.scheduling.enabled",
                defaultScheduling.enabled()
            ),
            integer(
                source,
                "jarvis.scheduling.max-delay-seconds",
                defaultScheduling.maxDelaySeconds()
            ),
            integer(
                source,
                "jarvis.scheduling.max-duration-seconds",
                defaultScheduling.maxDurationSeconds()
            )
        );

        JarvisConfig.Logging logging = new JarvisConfig.Logging(
            enumValue(
                source,
                "jarvis.logging.level",
                io.github.kardane.jarvisminecraft.common.logging.JarvisLogLevel.class,
                defaultLogging.level()
            ),
            bool(
                source,
                "jarvis.logging.console.enabled",
                defaultLogging.consoleEnabled()
            ),
            bool(
                source,
                "jarvis.logging.request.lifecycle",
                defaultLogging.requestLifecycle()
            ),
            bool(
                source,
                "jarvis.logging.ai.jev",
                defaultLogging.aiJev()
            ),
            bool(
                source,
                "jarvis.logging.ai.luna",
                defaultLogging.aiLuna()
            ),
            bool(
                source,
                "jarvis.logging.tool.lifecycle",
                defaultLogging.toolLifecycle()
            ),
            bool(
                source,
                "jarvis.logging.proactive.decisions",
                defaultLogging.proactiveDecisions()
            ),
            integer(
                source,
                "jarvis.logging.health.interval-seconds",
                defaultLogging.healthIntervalSeconds()
            )
        );

        return new JarvisConfig(
            interaction,
            model,
            personality,
            knowledge,
            response,
            conversationArchive,
            conversationMemory,
            execution,
            scheduling,
            logging
        );
    }

    private static String string(
        JarvisConfigSource source,
        String path,
        String fallback
    ) {
        return source.string(path).orElse(fallback);
    }

    private static boolean bool(
        JarvisConfigSource source,
        String path,
        boolean fallback
    ) {
        return source.bool(path).orElse(fallback);
    }

    private static int integer(
        JarvisConfigSource source,
        String path,
        int fallback
    ) {
        return source.integer(path).orElse(fallback);
    }

    private static double decimal(
        JarvisConfigSource source,
        String path,
        double fallback
    ) {
        return source.decimal(path).orElse(fallback);
    }

    private static List<String> list(
        JarvisConfigSource source,
        String path,
        List<String> fallback
    ) {
        return source.stringList(path).orElse(fallback);
    }

    private static <E extends Enum<E>> E enumValue(
        JarvisConfigSource source,
        String path,
        Class<E> enumType,
        E fallback
    ) {
        return source.string(path)
            .map(value -> {
                try {
                    return Enum.valueOf(
                        enumType,
                        value.trim().toUpperCase(Locale.ROOT)
                    );
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException(
                        "Invalid value for " + path + "."
                    );
                }
            })
            .orElse(fallback);
    }
}
