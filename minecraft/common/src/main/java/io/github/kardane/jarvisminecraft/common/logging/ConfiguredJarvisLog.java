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
        emit(JarvisLogLevel.DEBUG, event, null, fields);
    }

    @Override
    public void info(String event, Map<String, ?> fields) {
        emit(JarvisLogLevel.INFO, event, null, fields);
    }

    @Override
    public void warn(String event, Map<String, ?> fields) {
        emit(JarvisLogLevel.WARN, event, null, fields);
    }

    @Override
    public void error(
        String event,
        Throwable failure,
        Map<String, ?> fields
    ) {
        emit(JarvisLogLevel.ERROR, event, failure, fields);
    }

    private void emit(
        JarvisLogLevel level,
        String event,
        Throwable failure,
        Map<String, ?> fields
    ) {
        try {
            if (!enabled(level, event)) {
                return;
            }
            switch (level) {
                case DEBUG -> delegate.debug(event, fields);
                case INFO -> delegate.info(event, fields);
                case WARN -> delegate.warn(event, fields);
                case ERROR -> delegate.error(event, failure, fields);
            }
        } catch (RuntimeException ignored) {
            // Operational logging must never change runtime or Tool semantics.
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
                || event.startsWith("follow_up.")
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
