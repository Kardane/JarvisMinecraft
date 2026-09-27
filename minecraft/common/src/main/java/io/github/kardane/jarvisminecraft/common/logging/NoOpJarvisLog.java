package io.github.kardane.jarvisminecraft.common.logging;

import java.util.Map;

public enum NoOpJarvisLog implements JarvisLog {
    INSTANCE;

    @Override
    public void debug(String event, Map<String, ?> fields) {
    }

    @Override
    public void info(String event, Map<String, ?> fields) {
    }

    @Override
    public void warn(String event, Map<String, ?> fields) {
    }

    @Override
    public void error(
        String event,
        Throwable failure,
        Map<String, ?> fields
    ) {
    }
}
