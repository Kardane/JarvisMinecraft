package io.github.kardane.jarvisminecraft.common.config;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JarvisConfigVerificationMain {
    private JarvisConfigVerificationMain() {
    }

    public static void main(String[] args) {
        verifyDefaults();
        verifyPropertiesOverride();
        verifyInvalidConfigRejected();
        verifyPromptContentBoundsRejected();
        verifyReloadFailureKeepsPreviousSnapshot();
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
            "Properties integer override failed."
        );
        require(
            config.response().sound().enabled(),
            "Properties boolean override failed."
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

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
