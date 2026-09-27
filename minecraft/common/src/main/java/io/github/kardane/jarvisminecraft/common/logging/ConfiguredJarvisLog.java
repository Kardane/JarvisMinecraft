package io.github.kardane.jarvisminecraft.common.logging;

import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;

import java.util.Map;
import java.util.Objects;

public final class ConfiguredJarvisLog implements JarvisLog {
    private final ConfigManager configManager;
    private final JarvisLog delegate;

    public ConfiguredJarvisLog(
        ConfigManager configManager,
        JarvisLog delegate
    ) {
        this.configManager = Objects.requireNonNull(
            configManager,
            "configManager"
        );
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void debug(String event, Map<String, ?> fields) {
        if (enabled(JarvisLogLevel.DEBUG, event)) {
            delegate.debug(event, fields);
        }
    }

    @Override
    public void info(String event, Map<String, ?> fields) {
        if (enabled(JarvisLogLevel.INFO, event)) {
            delegate.info(event, fields);
        }
    }

    @Override
    public void warn(String event, Map<String, ?> fields) {
        if (enabled(JarvisLogLevel.WARN, event)) {
            delegate.warn(event, fields);
        }
    }

    @Override
    public void error(
        String event,
        Throwable failure,
        Map<String, ?> fields
    ) {
        if (enabled(JarvisLogLevel.ERROR, event)) {
            delegate.error(event, failure, fields);
        }
    }

    private boolean enabled(JarvisLogLevel level, String event) {
        JarvisConfig.Logging config = configManager.current().logging();
        return config.consoleEnabled()
            && config.level().allows(level)
            && categoryEnabled(config, event);
    }

    private boolean categoryEnabled(
        JarvisConfig.Logging config,
        String event
    ) {
        if (event == null) {
            return true;
        }
        if (event.startsWith("request.")) {
            return config.requestLifecycle();
        }
        if (
            event.startsWith("jev.")
                || event.startsWith("routing.")
                || event.startsWith("reasoning.")
        ) {
            return config.aiJev();
        }
        if (event.startsWith("luna.")) {
            return config.aiLuna();
        }
        if (event.startsWith("tool.")) {
            return config.toolLifecycle();
        }
        if (event.startsWith("proactive.")) {
            return config.proactiveDecisions();
        }
        return true;
    }
}
