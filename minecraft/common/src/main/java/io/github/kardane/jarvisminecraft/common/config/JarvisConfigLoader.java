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
        JarvisConfig.Response defaultResponse = defaults.response();
        JarvisConfig.WaitingMessage defaultWaiting =
            defaultResponse.waitingMessage();
        JarvisConfig.Sound defaultSound = defaultResponse.sound();
        JarvisConfig.Execution defaultExecution = defaults.execution();
        JarvisConfig.Scheduling defaultScheduling = defaults.scheduling();

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
            integer(
                source,
                "jarvis.interaction.follow-up-seconds",
                defaultInteraction.followUpSeconds()
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

        return new JarvisConfig(
            interaction,
            model,
            response,
            execution,
            scheduling
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
