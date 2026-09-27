package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.audit.AsyncJsonlAuditSink;
import io.github.kardane.jarvisminecraft.common.brain.ai.DeterministicRoutePolicy;
import io.github.kardane.jarvisminecraft.common.brain.ai.JdkJevClassifier;
import io.github.kardane.jarvisminecraft.common.brain.ai.LunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.OpenAiLunaClient;
import io.github.kardane.jarvisminecraft.common.brain.ai.ReasoningPolicy;
import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.protocol.ToolArgumentCodec;
import io.github.kardane.jarvisminecraft.common.runtime.CommonRuntime;
import io.github.kardane.jarvisminecraft.common.runtime.ExecutionPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ScheduledActionService;
import io.github.kardane.jarvisminecraft.common.runtime.SchedulingPolicy;
import io.github.kardane.jarvisminecraft.common.runtime.ToolRegistry;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
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
        ConfigManager configManager,
        ToolRegistry registry,
        CommonRuntime commonRuntime,
        Clock clock,
        JarvisLog log
    ) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(auditDirectory, "auditDirectory");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(configManager, "configManager");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(commonRuntime, "commonRuntime");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(log, "log");

        ExecutorService aiExecutor = Executors.newFixedThreadPool(
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
            new AsyncJsonlAuditSink(auditDirectory, clock);
        LunaClient luna = new OpenAiLunaClient(
            openAiApiKey,
            new ToolArgumentCodec()
        );

        try {
            EmbeddedBrain brain = new EmbeddedBrain(
                serverId,
                capabilities,
                sessions,
                new InMemoryConversationHistoryStore(),
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
                    registry.tools()
                ),
                clock,
                log
            );
            return new LiveRuntime(
                brain,
                luna,
                audit,
                aiExecutor
            );
        } catch (RuntimeException failure) {
            luna.close();
            aiExecutor.shutdownNow();
            audit.closeAsync();
            throw failure;
        }
    }

    record LiveRuntime(
        EmbeddedBrain brain,
        LunaClient luna,
        AsyncJsonlAuditSink audit,
        ExecutorService aiExecutor
    ) {
        LiveRuntime {
            Objects.requireNonNull(brain, "brain");
            Objects.requireNonNull(luna, "luna");
            Objects.requireNonNull(audit, "audit");
            Objects.requireNonNull(aiExecutor, "aiExecutor");
        }

        void closeOwnedResources() {
            luna.close();
            aiExecutor.shutdownNow();
            audit.closeAsync();
        }
    }
}
