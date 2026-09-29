package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.audit.AsyncJsonlAuditSink;
import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.JdkJevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.OpenAiLunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.ReasoningPolicy;
import io.github.kardane.jarvisminecraft.common.chat.ChatSessionManager;
import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
import io.github.kardane.jarvisminecraft.common.config.RuntimeConfigurationManager;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.prompt.PromptContentLoader;
import io.github.kardane.jarvisminecraft.common.prompt.PromptContentManager;
import io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;
import io.github.kardane.jarvisminecraft.common.protocol.ToolArgumentCodec;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ExecutionPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ScheduledActionService;
import io.github.kardane.jarvisminecraft.common.runtime.SchedulingPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class EmbeddedBrainBootstrap {
    private EmbeddedBrainBootstrap() {
    }

    static LiveRuntime create(
        String serverId,
        List<Capability> capabilities,
        String openAiApiKey,
        String typesafeApiKey,
        Path auditDirectory,
        ChatSessionManager sessions,
        RuntimeConfigurationManager runtimeConfiguration,
        ToolRegistry registry,
        CommonRuntime commonRuntime,
        Clock clock,
        JarvisLog log
    ) {
        Objects.requireNonNull(
            runtimeConfiguration,
            "runtimeConfiguration"
        );
        return createInternal(
            serverId,
            capabilities,
            openAiApiKey,
            typesafeApiKey,
            auditDirectory,
            sessions,
            runtimeConfiguration.configManager(),
            runtimeConfiguration.promptContentManager(),
            registry,
            commonRuntime,
            clock,
            log
        );
    }

    static LiveRuntime create(
        String serverId,
        List<Capability> capabilities,
        String openAiApiKey,
        String typesafeApiKey,
        Path auditDirectory,
        ChatSessionManager sessions,
        ConfigManager configManager,
        ToolRegistry registry,
        CommonRuntime commonRuntime,
        Clock clock,
        JarvisLog log
    ) {
        Objects.requireNonNull(
            configManager,
            "configManager"
        );
        JarvisConfig initialConfig =
            configManager.current();
        PromptContentManager promptContent =
            new PromptContentManager(
                promptLoader(
                    promptContentRoot(auditDirectory),
                    initialConfig
                )
            );
        return createInternal(
            serverId,
            capabilities,
            openAiApiKey,
            typesafeApiKey,
            auditDirectory,
            sessions,
            configManager,
            promptContent,
            registry,
            commonRuntime,
            clock,
            log
        );
    }

    private static LiveRuntime createInternal(
        String serverId,
        List<Capability> capabilities,
        String openAiApiKey,
        String typesafeApiKey,
        Path auditDirectory,
        ChatSessionManager sessions,
        ConfigManager configManager,
        PromptContentManager promptContent,
        ToolRegistry registry,
        CommonRuntime commonRuntime,
        Clock clock,
        JarvisLog log
    ) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(
            capabilities,
            "capabilities"
        );
        Objects.requireNonNull(
            auditDirectory,
            "auditDirectory"
        );
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(
            configManager,
            "configManager"
        );
        Objects.requireNonNull(
            promptContent,
            "promptContent"
        );
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(
            commonRuntime,
            "commonRuntime"
        );
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(log, "log");

        ExecutorService aiExecutor =
            Executors.newFixedThreadPool(
                AiRequestScheduler.DEFAULT_MAX_CONCURRENT,
                runnable -> {
                    Thread thread = new Thread(
                        runnable,
                        "jarvis-embedded-ai"
                    );
                    thread.setDaemon(true);
                    return thread;
                }
            );

        AsyncJsonlAuditSink audit =
            new AsyncJsonlAuditSink(
                auditDirectory,
                clock
            );
        AsyncConversationArchive conversationArchive =
            new AsyncConversationArchive(
                serverId,
                promptContentRoot(auditDirectory),
                configManager,
                clock,
                log
            );
        ConversationHistoryStore history =
            new ArchivingConversationHistoryStore(
                new InMemoryConversationHistoryStore(),
                conversationArchive
            );
        LunaClient luna = new OpenAiLunaClient(
            openAiApiKey,
            new ToolArgumentCodec()
        );

        try {
            EnumSet<ToolName> runtimeTools =
                EnumSet.noneOf(ToolName.class);
            runtimeTools.addAll(registry.registeredTools());
            runtimeTools.add(ToolName.SCHEDULE_ACTION);
            runtimeTools.add(ToolName.CANCEL_SCHEDULED_ACTION);
            runtimeTools.add(ToolName.WEB_SEARCH);
            registry.declarePolicyTools(runtimeTools);

            EmbeddedBrain brain = new EmbeddedBrain(
                serverId,
                capabilities,
                sessions,
                history,
                new AiRequestScheduler(aiExecutor),
                new JdkJevClassifier(
                    typesafeApiKey,
                    JdkJevClassifier.DEFAULT_ENDPOINT,
                    HttpClient.newBuilder()
                        .executor(aiExecutor)
                        .build(),
                    clock
                ),
                new DeterministicRoutePolicy(),
                luna,
                new ReasoningPolicy(configManager),
                new ExecutionPolicy(configManager),
                new SchedulingPolicy(configManager),
                new ScheduledActionService(clock),
                audit,
                commonRuntime.openRuntime(
                    UUID.randomUUID(),
                    serverId,
                    runtimeTools
                ),
                clock,
                log,
                promptContent::current,
                conversationArchive
            );
            return new LiveRuntime(
                brain,
                luna,
                promptContent,
                conversationArchive,
                audit,
                aiExecutor
            );
        } catch (RuntimeException failure) {
            luna.close();
            conversationArchive.close();
            aiExecutor.shutdownNow();
            audit.closeAsync();
            throw failure;
        }
    }

    private static PromptContentLoader promptLoader(
        Path configRoot,
        JarvisConfig config
    ) {
        JarvisConfig.Knowledge knowledge =
            config.knowledge();
        return new PromptContentLoader(
            configRoot,
            config.personality().enabled(),
            knowledge.enabled(),
            new PromptContentLoader.Limits(
                PromptContentLoader.Limits
                    .DEFAULT_PERSONA_MAX_BYTES,
                knowledge.maxFiles(),
                knowledge.maxFileBytes(),
                knowledge.maxTotalBytes()
            )
        );
    }

    private static Path promptContentRoot(
        Path auditDirectory
    ) {
        Path normalized = Objects.requireNonNull(
            auditDirectory,
            "auditDirectory"
        ).toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                "Audit directory must have a JARVIS data-directory parent."
            );
        }
        return parent;
    }

    record LiveRuntime(
        EmbeddedBrain brain,
        LunaClient luna,
        PromptContentManager promptContent,
        AsyncConversationArchive conversationArchive,
        AsyncJsonlAuditSink audit,
        ExecutorService aiExecutor
    ) {
        LiveRuntime {
            Objects.requireNonNull(brain, "brain");
            Objects.requireNonNull(luna, "luna");
            Objects.requireNonNull(
                promptContent,
                "promptContent"
            );
            Objects.requireNonNull(
                conversationArchive,
                "conversationArchive"
            );
            Objects.requireNonNull(audit, "audit");
            Objects.requireNonNull(
                aiExecutor,
                "aiExecutor"
            );
        }

        void closeOwnedResources() {
            luna.close();
            conversationArchive.close();
            aiExecutor.shutdownNow();
            audit.closeAsync();
        }
    }
}
