package io.github.kardane.jarvisminecraft.common.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class JarvisConfigVerificationMain {
    private JarvisConfigVerificationMain() {
    }

    public static void main(String[] args) throws Exception {
        verifyDefaults();
        verifyPropertiesOverride();
        verifyInvalidConfigRejected();
        verifyPromptContentBoundsRejected();
        verifyConversationArchiveBounds();
        verifyConversationMemoryBounds();
        verifyResponseUxDefaults();
        verifyReloadFailureKeepsPreviousSnapshot();
        verifyAtomicRuntimeReload();
        verifyAsyncReloadService();
        System.out.println("JarvisConfig verification OK");
    }

    private static void verifyDefaults() {
        JarvisConfig config = JarvisConfigLoader.load(
            JarvisConfigSource.empty()
        );
        require(
            config.interaction().mode()
                == JarvisConfig.InteractionMode.PASSIVE,
            "Default interaction mode must remain PASSIVE."
        );
        require(
            config.interaction().audience().mode()
                == JarvisConfig.AudienceMode.OP,
            "Default audience must remain OP."
        );
        require(
            config.interaction().followUpSeconds() == 30,
            "Default follow-up window must be 30 seconds."
        );
        require(
            "gpt-6-luna".equals(config.model().name()),
            "Model must remain pinned to gpt-6-luna."
        );
        require(
            config.execution().actors()
                == JarvisConfig.ExecutionActors.OP,
            "Execution actors must remain OP in Phase 1."
        );
        require(
            !config.scheduling().enabled(),
            "Scheduling must default to disabled."
        );
        require(
            !config.personality().enabled(),
            "Custom personality must default to disabled."
        );
        require(
            !config.knowledge().enabled(),
            "Custom knowledge must default to disabled."
        );
        require(
            !config.conversationArchive().enabled(),
            "Conversation archive must default to disabled."
        );
        require(
            !config.conversationMemory().enabled(),
            "Conversation memory must default to disabled."
        );
        require(
            config.conversationMemory().lookbackDays() == 30
                && config.conversationMemory().maxSourceFiles() == 4
                && config.conversationMemory().maxTurns() == 6
                && config.conversationMemory().maxContextBytes()
                    == 8 * 1024,
            "Conversation memory defaults changed unexpectedly."
        );
        require(
            config.conversationArchive().maxFileBytes()
                    == 1024 * 1024
                && config.conversationArchive().maxFiles() == 30,
            "Conversation archive defaults changed unexpectedly."
        );
        require(
            config.knowledge().maxFiles() == 32
                && config.knowledge().maxFileBytes() == 32 * 1024
                && config.knowledge().maxTotalBytes() == 128 * 1024,
            "Prompt-content limits must retain the documented defaults."
        );
    }

    private static void verifyPropertiesOverride() {
        Properties properties = new Properties();
        properties.setProperty(
            "jarvis.interaction.wake-words",
            "자비스|jarvis|비서"
        );
        properties.setProperty(
            "jarvis.interaction.follow-up-seconds",
            "90"
        );
        properties.setProperty(
            "jarvis.personality.enabled",
            "true"
        );
        properties.setProperty(
            "jarvis.knowledge.enabled",
            "true"
        );
        properties.setProperty(
            "jarvis.knowledge.max-files",
            "12"
        );
        properties.setProperty(
            "jarvis.knowledge.max-file-bytes",
            "16384"
        );
        properties.setProperty(
            "jarvis.knowledge.max-total-bytes",
            "65536"
        );
        properties.setProperty(
            "jarvis.conversation-archive.enabled",
            "true"
        );
        properties.setProperty(
            "jarvis.conversation-archive.max-file-bytes",
            "131072"
        );
        properties.setProperty(
            "jarvis.conversation-archive.max-files",
            "12"
        );
        properties.setProperty(
            "jarvis.conversation-memory.enabled",
            "true"
        );
        properties.setProperty(
            "jarvis.conversation-memory.lookback-days",
            "90"
        );
        properties.setProperty(
            "jarvis.conversation-memory.max-source-files",
            "6"
        );
        properties.setProperty(
            "jarvis.conversation-memory.max-turns",
            "5"
        );
        properties.setProperty(
            "jarvis.conversation-memory.max-context-bytes",
            "4096"
        );
        properties.setProperty(
            "jarvis.response.sound.enabled",
            "true"
        );
        properties.setProperty(
            "jarvis.response.sound.volume",
            "0.5"
        );

        JarvisConfig config = JarvisConfigLoader.load(
            PropertiesJarvisConfigSource.from(properties)
        );

        require(
            config.interaction().wakeWords().size() == 3,
            "Properties list parsing failed."
        );
        require(
            config.interaction().followUpSeconds() == 90,
            "Follow-up window override failed."
        );
        require(
            config.response().sound().enabled(),
            "Properties boolean override failed."
        );
        require(
            config.conversationArchive().enabled()
                && config.conversationArchive().maxFileBytes()
                    == 131072
                && config.conversationArchive().maxFiles()
                    == 12,
            "Conversation archive override failed."
        );
        require(
            config.conversationMemory().enabled()
                && config.conversationMemory().lookbackDays() == 90
                && config.conversationMemory().maxSourceFiles() == 6
                && config.conversationMemory().maxTurns() == 5
                && config.conversationMemory().maxContextBytes()
                    == 4096,
            "Conversation memory override failed."
        );
        require(
            config.personality().enabled(),
            "Personality boolean override failed."
        );
        require(
            config.knowledge().enabled()
                && config.knowledge().maxFiles() == 12
                && config.knowledge().maxFileBytes() == 16384
                && config.knowledge().maxTotalBytes() == 65536,
            "Knowledge bounds override failed."
        );
    }

    private static void verifyInvalidConfigRejected() {
        Properties properties = new Properties();
        properties.setProperty(
            "jarvis.interaction.follow-up-seconds",
            "0"
        );

        boolean rejected = false;
        try {
            JarvisConfigLoader.load(
                PropertiesJarvisConfigSource.from(properties)
            );
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "Invalid config must fail validation.");
    }

    private static void verifyPromptContentBoundsRejected() {
        Properties properties = new Properties();
        properties.setProperty(
            "jarvis.knowledge.max-files",
            "0"
        );

        boolean rejected = false;
        try {
            JarvisConfigLoader.load(
                PropertiesJarvisConfigSource.from(properties)
            );
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(
            rejected,
            "Knowledge limits must remain positive."
        );

        Properties inconsistent = new Properties();
        inconsistent.setProperty(
            "jarvis.knowledge.max-file-bytes",
            "32768"
        );
        inconsistent.setProperty(
            "jarvis.knowledge.max-total-bytes",
            "16384"
        );

        rejected = false;
        try {
            JarvisConfigLoader.load(
                PropertiesJarvisConfigSource.from(inconsistent)
            );
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(
            rejected,
            "Per-file knowledge limit must not exceed total knowledge limit."
        );
    }

    private static void verifyConversationArchiveBounds() {
        Properties tooSmall = new Properties();
        tooSmall.setProperty(
            "jarvis.conversation-archive.max-file-bytes",
            "1024"
        );

        boolean rejected = false;
        try {
            JarvisConfigLoader.load(
                PropertiesJarvisConfigSource.from(tooSmall)
            );
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(
            rejected,
            "Conversation archive file bound must be validated."
        );

        Properties tooMany = new Properties();
        tooMany.setProperty(
            "jarvis.conversation-archive.max-files",
            "366"
        );
        rejected = false;
        try {
            JarvisConfigLoader.load(
                PropertiesJarvisConfigSource.from(tooMany)
            );
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(
            rejected,
            "Conversation archive retention count must be validated."
        );
    }

    private static void verifyConversationMemoryBounds() {
        Properties invalid = new Properties();
        invalid.setProperty(
            "jarvis.conversation-memory.max-context-bytes",
            "512"
        );

        boolean rejected = false;
        try {
            JarvisConfigLoader.load(
                PropertiesJarvisConfigSource.from(invalid)
            );
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(
            rejected,
            "Conversation memory context bound must be validated."
        );

        invalid = new Properties();
        invalid.setProperty(
            "jarvis.conversation-memory.max-source-files",
            "17"
        );
        rejected = false;
        try {
            JarvisConfigLoader.load(
                PropertiesJarvisConfigSource.from(invalid)
            );
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(
            rejected,
            "Conversation memory source-file bound must be validated."
        );
    }

    private static void verifyResponseUxDefaults() {
        JarvisConfig.Response response =
            JarvisConfig.defaults().response();

        require(
            response.waitingMessage().messages().size() == 16,
            "Default waiting-message variation count must be 16."
        );
        require(
            response.metrics().enabled(),
            "Response metrics hover must default to enabled."
        );
        require(
            "<#7DD3FC>📊".equals(
                response.metrics().icon()
            ),
            "Response metrics default icon changed unexpectedly."
        );

        Properties properties = new Properties();
        properties.setProperty(
            "jarvis.response.metrics.enabled",
            "false"
        );
        properties.setProperty(
            "jarvis.response.metrics.icon",
            "ℹ️"
        );

        JarvisConfig overridden = JarvisConfigLoader.load(
            PropertiesJarvisConfigSource.from(properties)
        );
        require(
            !overridden.response().metrics().enabled()
                && "ℹ️".equals(
                    overridden.response().metrics().icon()
                ),
            "Response metrics configuration override failed."
        );
    }

    private static void verifyReloadFailureKeepsPreviousSnapshot() {
        AtomicBoolean fail = new AtomicBoolean(false);
        ConfigManager manager = new ConfigManager(() -> {
            if (fail.get()) {
                throw new IllegalArgumentException(
                    "synthetic invalid runtime config"
                );
            }
            return JarvisConfig.defaults();
        });

        JarvisConfig initial = manager.current();
        fail.set(true);
        ConfigManager.ReloadResult result = manager.reload();

        require(!result.success(), "Reload must report failure.");
        require(
            manager.current() == initial,
            "Failed reload must keep the previous immutable snapshot."
        );
    }

    private static void verifyAtomicRuntimeReload()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-runtime-config-"
        );
        Files.writeString(
            root.resolve("persona.md"),
            "FIRST PERSONA",
            StandardCharsets.UTF_8
        );
        Files.createDirectories(root.resolve("knowledge"));
        Files.writeString(
            root.resolve("knowledge").resolve("rules.md"),
            "RULES",
            StandardCharsets.UTF_8
        );

        AtomicBoolean failConfig =
            new AtomicBoolean(false);
        AtomicReference<JarvisConfig> candidate =
            new AtomicReference<>(
                JarvisConfig.defaults()
            );

        RuntimeConfigurationManager manager =
            new RuntimeConfigurationManager(
                root,
                () -> {
                    if (failConfig.get()) {
                        throw new IllegalArgumentException(
                            "synthetic invalid runtime config"
                        );
                    }
                    return candidate.get();
                }
            );

        RuntimeConfigurationManager.RuntimeSnapshot initial =
            manager.current();
        require(
            initial.promptContent().persona().isEmpty()
                && initial.promptContent()
                    .knowledge()
                    .isEmpty(),
            "Disabled prompt content must remain absent at startup."
        );

        candidate.set(
            promptConfig(
                true,
                true,
                8,
                16,
                32
            )
        );
        RuntimeConfigurationManager.ReloadResult success =
            manager.reload();

        require(
            success.success(),
            "Valid runtime config + prompt content must commit."
        );
        RuntimeConfigurationManager.RuntimeSnapshot active =
            manager.current();
        require(
            active != initial,
            "Successful reload must publish a new composite snapshot."
        );
        require(
            active.config().personality().enabled()
                && active.config().knowledge().enabled(),
            "Successful reload did not publish prompt enablement."
        );
        require(
            "FIRST PERSONA".equals(
                active.promptContent().persona()
            ),
            "Successful reload did not publish persona content."
        );
        require(
            active.promptContent().knowledge().size() == 1,
            "Successful reload did not publish knowledge content."
        );
        require(
            manager.configManager().current()
                == active.config()
                && manager.promptContentManager().current()
                    == active.promptContent(),
            "Managed views do not reference the same composite snapshot."
        );

        Files.writeString(
            root.resolve("knowledge").resolve("rules.md"),
            "TOO-LONG",
            StandardCharsets.UTF_8
        );
        candidate.set(
            promptConfig(
                true,
                true,
                8,
                4,
                8
            )
        );

        RuntimeConfigurationManager.ReloadResult
            invalidPrompt = manager.reload();
        require(
            !invalidPrompt.success(),
            "Invalid prompt content must reject the reload."
        );
        require(
            manager.current() == active,
            "Invalid prompt content partially replaced the active runtime snapshot."
        );
        require(
            manager.configManager().current()
                == active.config()
                && manager.promptContentManager().current()
                    == active.promptContent(),
            "Invalid prompt content changed one managed view."
        );

        failConfig.set(true);
        RuntimeConfigurationManager.ReloadResult
            invalidConfig = manager.reload();
        require(
            !invalidConfig.success(),
            "Invalid structured config must reject the reload."
        );
        require(
            manager.current() == active,
            "Invalid structured config partially replaced the active runtime snapshot."
        );

        RuntimeConfigurationManager.ReloadResult
            repeatedFailure = manager.reload();
        require(
            !repeatedFailure.success()
                && manager.current() == active,
            "Repeated failed reloads corrupted the active runtime snapshot."
        );
    }

    private static void verifyAsyncReloadService()
        throws Exception {
        Path root = Files.createTempDirectory(
            "jarvis-runtime-reload-service-"
        );
        AtomicReference<String> loaderThread =
            new AtomicReference<>();

        RuntimeConfigurationManager manager =
            new RuntimeConfigurationManager(
                root,
                () -> {
                    loaderThread.set(
                        Thread.currentThread().getName()
                    );
                    return JarvisConfig.defaults();
                }
            );

        RuntimeConfigurationReloadService service =
            new RuntimeConfigurationReloadService(manager);
        String callerThread =
            Thread.currentThread().getName();

        RuntimeConfigurationManager.ReloadResult result =
            service.reloadAsync()
                .toCompletableFuture()
                .join();

        require(
            result.success(),
            "Asynchronous runtime reload must complete successfully."
        );
        require(
            !callerThread.equals(loaderThread.get())
                && "jarvis-config-reload".equals(
                    loaderThread.get()
                ),
            "Runtime reload file/config loading must execute off the caller thread."
        );

        service.close();

        boolean rejected = false;
        try {
            service.reloadAsync()
                .toCompletableFuture()
                .join();
        } catch (RuntimeException expected) {
            rejected = true;
        }
        require(
            rejected,
            "Closed runtime reload service must reject new reloads."
        );
    }

    private static JarvisConfig promptConfig(
        boolean personalityEnabled,
        boolean knowledgeEnabled,
        int maxFiles,
        int maxFileBytes,
        int maxTotalBytes
    ) {
        Properties properties = new Properties();
        properties.setProperty(
            "jarvis.personality.enabled",
            Boolean.toString(personalityEnabled)
        );
        properties.setProperty(
            "jarvis.knowledge.enabled",
            Boolean.toString(knowledgeEnabled)
        );
        properties.setProperty(
            "jarvis.knowledge.max-files",
            Integer.toString(maxFiles)
        );
        properties.setProperty(
            "jarvis.knowledge.max-file-bytes",
            Integer.toString(maxFileBytes)
        );
        properties.setProperty(
            "jarvis.knowledge.max-total-bytes",
            Integer.toString(maxTotalBytes)
        );
        return JarvisConfigLoader.load(
            PropertiesJarvisConfigSource.from(properties)
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
