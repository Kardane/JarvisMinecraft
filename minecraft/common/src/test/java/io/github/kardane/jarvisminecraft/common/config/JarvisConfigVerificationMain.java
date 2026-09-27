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
