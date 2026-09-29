package io.github.kardane.jarvisminecraft.common.logging;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class JarvisStatsFormatter {
    private JarvisStatsFormatter() {
    }

    public static List<JarvisStatusFormatter.StatusLine> styledLines(
        RuntimeStatistics.Snapshot stats
    ) {
        Objects.requireNonNull(stats, "stats");
        List<JarvisStatusFormatter.StatusLine> lines =
            new ArrayList<>();

        lines.add(
            JarvisStatusFormatter.StatusLine.title(
                "JARVIS · STATS"
            )
        );

        lines.add(
            JarvisStatusFormatter.StatusLine.section(
                "Requests"
            )
        );
        lines.add(field(
            "Since",
            stats.startedAt().toString()
        ));
        lines.add(field(
            "Accepted",
            number(stats.requestsAccepted())
        ));
        lines.add(field(
            "Succeeded",
            number(stats.requestsSucceeded())
        ));
        lines.add(field(
            "Failed",
            number(stats.requestsFailed())
        ));
        lines.add(field(
            "Success rate",
            percent(stats.requestSuccessRate())
        ));
        lines.add(field(
            "Avg latency",
            millis(stats.averageRequestLatencyMillis())
        ));
        lines.add(field(
            "P95 latency",
            millis(stats.p95RequestLatencyMillis())
        ));

        lines.add(
            JarvisStatusFormatter.StatusLine.section(
                "Jev"
            )
        );
        lines.add(field(
            "Classified",
            number(stats.jevCompleted())
        ));
        lines.add(field(
            "Failed",
            number(stats.jevFailed())
        ));
        lines.add(field(
            "Fallbacks",
            number(stats.jevFallbacks())
        ));
        lines.add(field(
            "Avg latency",
            millis(stats.averageJevLatencyMillis())
        ));
        lines.add(field(
            "Reasoning confidence",
            percent(stats.averageReasoningConfidence())
        ));
        for (String level :
            List.of("NONE", "LOW", "MEDIUM", "HIGH")) {
            lines.add(field(
                "Jev " + level,
                countAndPercent(
                    stats.jevReasoningCounts()
                        .getOrDefault(level, 0L),
                    stats.jevCompleted()
                )
            ));
        }

        lines.add(
            JarvisStatusFormatter.StatusLine.section(
                "Resolved reasoning"
            )
        );
        long resolvedTotal =
            stats.resolvedReasoningTotal();
        for (String level :
            List.of("NONE", "LOW", "MEDIUM", "HIGH")) {
            lines.add(field(
                level,
                countAndPercent(
                    stats.resolvedReasoningCounts()
                        .getOrDefault(level, 0L),
                    resolvedTotal
                )
            ));
        }

        lines.add(
            JarvisStatusFormatter.StatusLine.section(
                "Routes"
            )
        );
        stats.routeCounts().entrySet().stream()
            .sorted(
                Map.Entry.<String, Long>comparingByValue(
                    Comparator.reverseOrder()
                ).thenComparing(Map.Entry.comparingByKey())
            )
            .limit(4)
            .forEach(entry ->
                lines.add(field(
                    entry.getKey(),
                    countAndPercent(
                        entry.getValue(),
                        stats.routeTotal()
                    )
                ))
            );

        lines.add(
            JarvisStatusFormatter.StatusLine.section(
                "Luna"
            )
        );
        lines.add(field(
            "Rounds",
            number(stats.lunaRounds())
        ));
        lines.add(field(
            "Avg round latency",
            millis(
                stats.averageLunaRoundLatencyMillis()
            )
        ));
        lines.add(field(
            "Input tokens",
            number(stats.lunaInputTokens())
        ));
        lines.add(field(
            "Output tokens",
            number(stats.lunaOutputTokens())
        ));
        lines.add(field(
            "Total tokens",
            number(stats.lunaTotalTokens())
        ));
        lines.add(field(
            "Web searches",
            number(stats.webSearchCalls())
        ));

        lines.add(
            JarvisStatusFormatter.StatusLine.section(
                "Tools"
            )
        );
        lines.add(field(
            "Calls",
            number(stats.toolCalls())
        ));
        lines.add(field(
            "Failed",
            number(stats.toolFailures())
        ));
        lines.add(field(
            "Denied",
            number(stats.toolDenied())
        ));

        stats.toolCounts().entrySet().stream()
            .sorted(
                Map.Entry.<String, Long>comparingByValue(
                    Comparator.reverseOrder()
                ).thenComparing(Map.Entry.comparingByKey())
            )
            .limit(3)
            .forEach(entry ->
                lines.add(field(
                    "Top " + entry.getKey(),
                    number(entry.getValue())
                ))
            );

        return List.copyOf(lines);
    }

    private static JarvisStatusFormatter.StatusLine field(
        String label,
        String value
    ) {
        return JarvisStatusFormatter.StatusLine.field(
            label,
            value,
            JarvisStatusFormatter.VALUE_RGB
        );
    }

    private static String millis(double value) {
        if (value < 1_000.0) {
            return String.format(
                Locale.ROOT,
                "%.0f ms",
                value
            );
        }
        return String.format(
            Locale.ROOT,
            "%.2f s",
            value / 1_000.0
        );
    }

    private static String percent(double value) {
        return String.format(
            Locale.ROOT,
            "%.1f%%",
            value * 100.0
        );
    }

    private static String countAndPercent(
        long count,
        long total
    ) {
        double ratio =
            total == 0L ? 0.0 : count / (double) total;
        return number(count)
            + " ("
            + percent(ratio)
            + ")";
    }

    private static String number(long value) {
        return String.format(
            Locale.ROOT,
            "%,d",
            value
        );
    }
}
