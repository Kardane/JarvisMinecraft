package io.github.kardane.jarvisminecraft.paper.logging;

import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;
import io.github.kardane.jarvisminecraft.common.logging.LogSanitizer;

import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PaperJarvisLog implements JarvisLog {
    private final Logger logger;

    public PaperJarvisLog(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override public void debug(String event, Map<String, ?> fields) {
        logger.fine(LogSanitizer.formatEvent(event, fields));
    }
    @Override public void info(String event, Map<String, ?> fields) {
        logger.info(LogSanitizer.formatEvent(event, fields));
    }
    @Override public void warn(String event, Map<String, ?> fields) {
        logger.warning(LogSanitizer.formatEvent(event, fields));
    }
    @Override public void error(String event, Throwable failure, Map<String, ?> fields) {
        String message = LogSanitizer.formatEvent(event, fields);
        if (failure == null) logger.severe(message);
        else logger.log(Level.SEVERE, message, failure);
    }
}
