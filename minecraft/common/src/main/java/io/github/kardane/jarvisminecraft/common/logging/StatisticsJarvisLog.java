package io.github.kardane.jarvisminecraft.common.logging;

import java.util.Map;
import java.util.Objects;

public final class StatisticsJarvisLog implements JarvisLog {
    private final RuntimeStatistics statistics;
    private final JarvisLog delegate;

    public StatisticsJarvisLog(
        RuntimeStatistics statistics,
        JarvisLog delegate
    ) {
        this.statistics = Objects.requireNonNull(
            statistics,
            "statistics"
        );
        this.delegate = Objects.requireNonNull(
            delegate,
            "delegate"
        );
    }

    @Override
    public void debug(String event, Map<String, ?> fields) {
        statistics.record(event, fields);
        delegate.debug(event, fields);
    }

    @Override
    public void info(String event, Map<String, ?> fields) {
        statistics.record(event, fields);
        delegate.info(event, fields);
    }

    @Override
    public void warn(String event, Map<String, ?> fields) {
        statistics.record(event, fields);
        delegate.warn(event, fields);
    }

    @Override
    public void error(
        String event,
        Throwable failure,
        Map<String, ?> fields
    ) {
        statistics.record(event, fields);
        delegate.error(event, failure, fields);
    }
}
