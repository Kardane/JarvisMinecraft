package io.github.kardane.jarvisminecraft.common.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.kardane.jarvisminecraft.common.logging.JarvisLogLevel;
import io.github.kardane.jarvisminecraft.common.prompt.PromptContentLoader;


public record JarvisConfig(
    Interaction interaction,
    Model model,
    Personality personality,
    Knowledge knowledge,
    Response response,
    ConversationArchive conversationArchive,
    ConversationMemory conversationMemory,
    Execution execution,
    Scheduling scheduling,
    Logging logging
) {
    public JarvisConfig {
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(personality, "personality");
        Objects.requireNonNull(knowledge, "knowledge");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(
            conversationArchive,
            "conversationArchive"
        );
        Objects.requireNonNull(
            conversationMemory,
            "conversationMemory"
        );
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(scheduling, "scheduling");
        Objects.requireNonNull(logging, "logging");
    }

    public JarvisConfig(
        Interaction interaction,
        Model model,
        Personality personality,
        Knowledge knowledge,
        Response response,
        ConversationArchive conversationArchive,
        Execution execution,
        Scheduling scheduling,
        Logging logging
    ) {
        this(
            interaction,
            model,
            personality,
            knowledge,
            response,
            conversationArchive,
            ConversationMemory.defaults(),
            execution,
            scheduling,
            logging
        );
    }

    public JarvisConfig(
        Interaction interaction,
        Model model,
        Personality personality,
        Knowledge knowledge,
        Response response,
        Execution execution,
        Scheduling scheduling,
        Logging logging
    ) {
        this(
            interaction,
            model,
            personality,
            knowledge,
            response,
            ConversationArchive.defaults(),
            ConversationMemory.defaults(),
            execution,
            scheduling,
            logging
        );
    }

    public JarvisConfig(
        Interaction interaction,
        Model model,
        Response response,
        Execution execution,
        Scheduling scheduling,
        Logging logging
    ) {
        this(
            interaction,
            model,
            Personality.defaults(),
            Knowledge.defaults(),
            response,
            ConversationArchive.defaults(),
            ConversationMemory.defaults(),
            execution,
            scheduling,
            logging
        );
    }

    public JarvisConfig(
        Interaction interaction,
        Model model,
        Response response,
        Execution execution,
        Scheduling scheduling
    ) {
        this(
            interaction,
            model,
            Personality.defaults(),
            Knowledge.defaults(),
            response,
            ConversationArchive.defaults(),
            ConversationMemory.defaults(),
            execution,
            scheduling,
            Logging.defaults()
        );
    }

    public static JarvisConfig defaults() {
        return new JarvisConfig(
            new Interaction(
                InteractionMode.PASSIVE,
                List.of("자비스", "jarvis", "재비스"),
                WakeWordMatching.defaults(),
                30,
                0.70,
                new Audience(AudienceMode.OP, List.of(), List.of()),
                new Proactive(12, 15, 0.75)
            ),
            new Model(
                "gpt-6-luna",
                new Reasoning(ReasoningMode.AUTO, ReasoningMode.MEDIUM)
            ),
            Personality.defaults(),
            Knowledge.defaults(),
            new Response(
                "[JARVIS] ",
                new WaitingMessage(
                    true,
                    1_800,
                    List.of(
                        "<#94A3B8>잠시만요.&r",
                        "<#7DD3FC>확인해볼게요.&r",
                        "<#94A3B8>서버 상태를 살펴보고 있어요.&r",
                        "<#7DD3FC>조금만 기다려 주세요.&r",
                        "<#94A3B8>요청을 확인하고 있어요.&r",
                        "<#7DD3FC>필요한 정보를 모으고 있어요.&r",
                        "<#94A3B8>서버 데이터를 확인 중이에요.&r",
                        "<#7DD3FC>관련 상태를 조회하고 있어요.&r",
                        "<#94A3B8>요청 내용을 정리하고 있어요.&r",
                        "<#7DD3FC>확인할 항목을 살펴보고 있어요.&r",
                        "<#94A3B8>잠깐 확인해볼게요.&r",
                        "<#7DD3FC>처리할 내용을 점검하고 있어요.&r",
                        "<#94A3B8>서버에서 확인 중이에요.&r",
                        "<#7DD3FC>필요한 상태를 불러오고 있어요.&r",
                        "<#94A3B8>요청을 처리하고 있어요.&r",
                        "<#7DD3FC>곧 답변드릴게요.&r"
                    )
                ),
                new Sound(
                    false,
                    "minecraft:block.note_block.pling",
                    1.0,
                    1.0
                ),
                ResponseMetrics.defaults()
            ),
            ConversationArchive.defaults(),
            ConversationMemory.defaults(),
            new Execution(ExecutionMode.READ_TALK, ExecutionActors.OP),
            new Scheduling(true, 60, 60),
            Logging.defaults()
        );
    }

    public enum InteractionMode {
        PASSIVE,
        ACTIVE
    }

    public enum AudienceMode {
        OP,
        WHITELIST,
        ALL,
        BLACKLIST
    }

    public enum ReasoningMode {
        AUTO,
        NONE,
        LOW,
        MEDIUM,
        HIGH
    }

    public enum ExecutionMode {
        READ_TALK,
        EXECUTE_LITE,
        EXECUTE
    }

    /**
     * Interaction audience expansion does not change Tool authority: only
     * online OPs may receive Minecraft Tools in the current runtime. Additional
     * execution actor modes require a later contract change.
     */
    public enum ExecutionActors {
        OP
    }

    public record Interaction(
        InteractionMode mode,
        List<String> wakeWords,
        WakeWordMatching wakeWordMatching,
        int followUpSeconds,
        double followUpConfidenceThreshold,
        Audience audience,
        Proactive proactive
    ) {
        public Interaction(
            InteractionMode mode,
            List<String> wakeWords,
            int followUpSeconds,
            Audience audience,
            Proactive proactive
        ) {
            this(
                mode,
                wakeWords,
                WakeWordMatching.defaults(),
                followUpSeconds,
                0.70,
                audience,
                proactive
            );
        }

        public Interaction {
            Objects.requireNonNull(mode, "interaction.mode");
            wakeWords = normalizedList(
                wakeWords,
                "interaction.wakeWords",
                1,
                32,
                64
            );
            Objects.requireNonNull(
                wakeWordMatching,
                "interaction.wakeWordMatching"
            );
            requireRange(
                followUpSeconds,
                1,
                600,
                "interaction.followUpSeconds"
            );
            requireProbability(
                followUpConfidenceThreshold,
                "interaction.followUpConfidenceThreshold"
            );
            Objects.requireNonNull(audience, "interaction.audience");
            Objects.requireNonNull(proactive, "interaction.proactive");
        }
    }

    public record WakeWordMatching(
        boolean anywhere,
        boolean fuzzyEnabled,
        int maxEditDistance
    ) {
        public WakeWordMatching {
            requireRange(
                maxEditDistance,
                0,
                1,
                "interaction.wakeWordMatching.maxEditDistance"
            );
        }

        public static WakeWordMatching defaults() {
            return new WakeWordMatching(
                true,
                true,
                1
            );
        }
    }

    public record Audience(
        AudienceMode mode,
        List<String> whitelist,
        List<String> blacklist
    ) {
        public Audience {
            Objects.requireNonNull(mode, "interaction.audience.mode");
            whitelist = normalizedList(
                whitelist,
                "interaction.audience.whitelist",
                0,
                256,
                64
            );
            blacklist = normalizedList(
                blacklist,
                "interaction.audience.blacklist",
                0,
                256,
                64
            );
        }
    }

    public record Proactive(
        int contextMessages,
        int cooldownSeconds,
        double confidenceThreshold
    ) {
        public Proactive {
            requireRange(
                contextMessages,
                1,
                50,
                "interaction.proactive.contextMessages"
            );
            requireRange(
                cooldownSeconds,
                0,
                300,
                "interaction.proactive.cooldownSeconds"
            );
            requireProbability(
                confidenceThreshold,
                "interaction.proactive.confidenceThreshold"
            );
        }
    }

    public record Model(
        String name,
        Reasoning reasoning
    ) {
        public Model {
            name = requireText(name, "model.name", 64);
            if (!"gpt-6-luna".equals(name)) {
                throw new IllegalArgumentException(
                    "model.name must remain gpt-6-luna in the current product contract."
                );
            }
            Objects.requireNonNull(reasoning, "model.reasoning");
        }
    }

    public record Reasoning(
        ReasoningMode mode,
        ReasoningMode fallback
    ) {
        public Reasoning {
            Objects.requireNonNull(mode, "model.reasoning.mode");
            Objects.requireNonNull(fallback, "model.reasoning.fallback");
            if (fallback == ReasoningMode.AUTO) {
                throw new IllegalArgumentException(
                    "model.reasoning.fallback must be a concrete reasoning level."
                );
            }
        }
    }

    public record Personality(
        boolean enabled
    ) {
        public static Personality defaults() {
            return new Personality(true);
        }
    }

    public record Knowledge(
        boolean enabled,
        int maxFiles,
        int maxFileBytes,
        int maxTotalBytes
    ) {
        public Knowledge {
            requireRange(
                maxFiles,
                1,
                Integer.MAX_VALUE,
                "knowledge.maxFiles"
            );
            requireRange(
                maxFileBytes,
                1,
                Integer.MAX_VALUE,
                "knowledge.maxFileBytes"
            );
            requireRange(
                maxTotalBytes,
                1,
                Integer.MAX_VALUE,
                "knowledge.maxTotalBytes"
            );
            if (maxFileBytes > maxTotalBytes) {
                throw new IllegalArgumentException(
                    "knowledge.maxFileBytes must not exceed knowledge.maxTotalBytes."
                );
            }
        }

        public static Knowledge defaults() {
            return new Knowledge(
                true,
                PromptContentLoader.Limits.DEFAULT_KNOWLEDGE_MAX_FILES,
                PromptContentLoader.Limits.DEFAULT_KNOWLEDGE_MAX_FILE_BYTES,
                PromptContentLoader.Limits.DEFAULT_KNOWLEDGE_MAX_TOTAL_BYTES
            );
        }
    }

    public record Response(
        String prefix,
        WaitingMessage waitingMessage,
        Sound sound,
        ResponseMetrics metrics
    ) {
        public Response(
            String prefix,
            WaitingMessage waitingMessage,
            Sound sound
        ) {
            this(
                prefix,
                waitingMessage,
                sound,
                ResponseMetrics.defaults()
            );
        }

        public Response {
            prefix = requireTextAllowEmpty(prefix, "response.prefix", 128);
            Objects.requireNonNull(
                waitingMessage,
                "response.waitingMessage"
            );
            Objects.requireNonNull(sound, "response.sound");
            Objects.requireNonNull(metrics, "response.metrics");
        }
    }

    public record WaitingMessage(
        boolean enabled,
        int thresholdMillis,
        List<String> messages
    ) {
        public WaitingMessage {
            requireRange(
                thresholdMillis,
                0,
                30_000,
                "response.waitingMessage.thresholdMillis"
            );
            messages = normalizedList(
                messages,
                "response.waitingMessage.messages",
                enabled ? 1 : 0,
                16,
                256
            );
        }
    }

    public record Sound(
        boolean enabled,
        String id,
        double volume,
        double pitch
    ) {
        public Sound {
            id = requireText(id, "response.sound.id", 128);
            requireFiniteRange(volume, 0.0, 4.0, "response.sound.volume");
            requireFiniteRange(pitch, 0.01, 2.0, "response.sound.pitch");
        }
    }

    public record ResponseMetrics(
        boolean enabled,
        String icon
    ) {
        public ResponseMetrics {
            icon = requireText(
                icon,
                "response.metrics.icon",
                64
            );
        }

        public static ResponseMetrics defaults() {
            return new ResponseMetrics(
                true,
                "<#7DD3FC>📊"
            );
        }
    }

    public record ConversationArchive(
        boolean enabled,
        int maxFileBytes,
        int maxFiles
    ) {
        public ConversationArchive {
            requireRange(
                maxFileBytes,
                64 * 1024,
                16 * 1024 * 1024,
                "conversationArchive.maxFileBytes"
            );
            requireRange(
                maxFiles,
                1,
                365,
                "conversationArchive.maxFiles"
            );
        }

        public static ConversationArchive defaults() {
            return new ConversationArchive(
                true,
                1024 * 1024,
                30
            );
        }
    }

    public record ConversationMemory(
        boolean enabled,
        int lookbackDays,
        int maxSourceFiles,
        int maxTurns,
        int maxContextBytes
    ) {
        public ConversationMemory {
            requireRange(
                lookbackDays,
                1,
                3650,
                "conversationMemory.lookbackDays"
            );
            requireRange(
                maxSourceFiles,
                1,
                16,
                "conversationMemory.maxSourceFiles"
            );
            requireRange(
                maxTurns,
                1,
                12,
                "conversationMemory.maxTurns"
            );
            requireRange(
                maxContextBytes,
                1024,
                32 * 1024,
                "conversationMemory.maxContextBytes"
            );
        }

        public static ConversationMemory defaults() {
            return new ConversationMemory(
                true,
                30,
                4,
                6,
                8 * 1024
            );
        }
    }

    public record Execution(
        ExecutionMode mode,
        ExecutionActors actors
    ) {
        public Execution {
            Objects.requireNonNull(mode, "execution.mode");
            Objects.requireNonNull(actors, "execution.actors");
        }
    }

    public record Scheduling(
        boolean enabled,
        int maxDelaySeconds,
        int maxDurationSeconds
    ) {
        public Scheduling {
            requireRange(
                maxDelaySeconds,
                1,
                60,
                "scheduling.maxDelaySeconds"
            );
            requireRange(
                maxDurationSeconds,
                1,
                60,
                "scheduling.maxDurationSeconds"
            );
        }
    }

    public record Logging(
        JarvisLogLevel level,
        boolean consoleEnabled,
        boolean requestLifecycle,
        boolean aiJev,
        boolean aiLuna,
        boolean toolLifecycle,
        boolean proactiveDecisions,
        int healthIntervalSeconds
    ) {
        public Logging {
            Objects.requireNonNull(level, "logging.level");
            requireRange(
                healthIntervalSeconds,
                1,
                3600,
                "logging.health.intervalSeconds"
            );
        }

        public static Logging defaults() {
            return new Logging(
                JarvisLogLevel.INFO,
                true,
                true,
                true,
                true,
                true,
                false,
                30
            );
        }
    }

    private static List<String> normalizedList(
        List<String> values,
        String field,
        int minSize,
        int maxSize,
        int maxEntryLength
    ) {
        Objects.requireNonNull(values, field);
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(requireText(value, field, maxEntryLength));
        }
        if (normalized.size() < minSize || normalized.size() > maxSize) {
            throw new IllegalArgumentException(
                field + " must contain between " + minSize + " and " + maxSize + " unique entries."
            );
        }
        return List.copyOf(normalized);
    }

    private static String requireText(
        String value,
        String field,
        int maxLength
    ) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                field + " must be non-blank and at most " + maxLength + " characters."
            );
        }
        return normalized;
    }

    private static String requireTextAllowEmpty(
        String value,
        String field,
        int maxLength
    ) {
        Objects.requireNonNull(value, field);
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                field + " must be at most " + maxLength + " characters."
            );
        }
        return value;
    }

    private static void requireRange(
        int value,
        int min,
        int max,
        String field
    ) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                field + " must be between " + min + " and " + max + "."
            );
        }
    }

    private static void requireProbability(double value, String field) {
        requireFiniteRange(value, 0.0, 1.0, field);
    }

    private static void requireFiniteRange(
        double value,
        double min,
        double max,
        String field
    ) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(
                field + " must be between " + min + " and " + max + "."
            );
        }
    }
}
