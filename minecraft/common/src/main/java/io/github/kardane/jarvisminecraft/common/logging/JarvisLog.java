package io.github.kardane.jarvisminecraft.common.logging;

import java.util.Map;

public interface JarvisLog {
    void debug(String event, Map<String, ?> fields);

    void info(String event, Map<String, ?> fields);

    void warn(String event, Map<String, ?> fields);

    void error(String event, Throwable failure, Map<String, ?> fields);
}
