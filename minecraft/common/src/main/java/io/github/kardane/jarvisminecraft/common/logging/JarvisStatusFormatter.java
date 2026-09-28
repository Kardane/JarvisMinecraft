package io.github.kardane.jarvisminecraft.common.logging;

import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class JarvisStatusFormatter {
    public static final int TITLE_RGB = 0x7DD3FC;
    public static final int SECTION_RGB = 0xA78BFA;
    public static final int KEY_RGB = 0x94A3B8;
    public static final int VALUE_RGB = 0xE2E8F0;
    public static final int GOOD_RGB = 0x86EFAC;
    public static final int WARN_RGB = 0xFDE68A;
    public static final int BAD_RGB = 0xFCA5A5;
    public static final int MUTED_RGB = 0x64748B;

    private JarvisStatusFormatter() {
    }

    public static List<StatusLine> styledLines(
        BrainGateway.StatusSnapshot status
    ) {
        Objects.requireNonNull(status, "status");
        List<StatusLine> lines = new ArrayList<>();

        lines.add(StatusLine.title("JARVIS · STATUS"));
        lines.add(StatusLine.section("Runtime"));
        lines.add(StatusLine.field(
            "Runtime",
            String.valueOf(status.runtime()),
            stateColor(String.valueOf(status.runtime()))
        ));
        lines.add(StatusLine.field(
            "Interaction",
            String.valueOf(status.interactionMode()),
            VALUE_RGB
        ));
        lines.add(StatusLine.field(
            "Audience",
            String.valueOf(status.audienceMode()),
            VALUE_RGB
        ));
        lines.add(StatusLine.field(
            "Execution",
            String.valueOf(status.executionMode()),
            VALUE_RGB
        ));
        lines.add(StatusLine.field(
            "Scheduling",
            status.schedulingEnabled() ? "enabled" : "disabled",
            status.schedulingEnabled() ? GOOD_RGB : MUTED_RGB
        ));

        lines.add(StatusLine.section("AI"));
        lines.add(StatusLine.field(
            "Queue",
            status.aiQueued() + " / " + status.aiQueueCapacity(),
            capacityColor(status.aiQueued(), status.aiQueueCapacity())
        ));
        lines.add(StatusLine.field(
            "Active",
            status.aiActive() + " / " + status.aiConcurrentCapacity(),
            capacityColor(
                status.aiActive(),
                status.aiConcurrentCapacity()
            )
        ));
        lines.add(StatusLine.field(
            "Proactive in-flight",
            status.proactiveInFlight() ? "yes" : "no",
            status.proactiveInFlight() ? VALUE_RGB : MUTED_RGB
        ));

        BrainGateway.AuditHealth audit = status.audit();
        lines.add(StatusLine.section("Audit"));
        lines.add(StatusLine.field(
            "Status",
            String.valueOf(audit.status()),
            stateColor(String.valueOf(audit.status()))
        ));
        lines.add(StatusLine.field(
            "Queue",
            audit.queueDepth() + " / " + audit.maxQueue(),
            capacityColor(audit.queueDepth(), audit.maxQueue())
        ));
        lines.add(StatusLine.field(
            "Writable",
            Boolean.toString(audit.writable()),
            audit.writable() ? GOOD_RGB : BAD_RGB
        ));
        lines.add(StatusLine.field(
            "Rejected",
            Long.toString(audit.rejectedRecords()),
            audit.rejectedRecords() == 0 ? GOOD_RGB : WARN_RGB
        ));
        lines.add(StatusLine.field(
            "Files",
            Integer.toString(audit.fileCount()),
            VALUE_RGB
        ));
        lines.add(StatusLine.field(
            "Size",
            humanBytes(audit.totalBytes()),
            VALUE_RGB
        ));
        if (audit.lastSuccessfulWriteAt() != null) {
            lines.add(StatusLine.field(
                "Last write",
                audit.lastSuccessfulWriteAt().toString(),
                MUTED_RGB
            ));
        }
        if (audit.lastErrorCode() != null) {
            lines.add(StatusLine.field(
                "Last error",
                audit.lastErrorCode(),
                BAD_RGB
            ));
        }
        return List.copyOf(lines);
    }

    public static List<String> lines(
        BrainGateway.StatusSnapshot status
    ) {
        return styledLines(status).stream()
            .map(StatusLine::plainText)
            .toList();
    }

    private static int capacityColor(
        long current,
        long maximum
    ) {
        if (maximum <= 0) {
            return MUTED_RGB;
        }
        double ratio = current / (double) maximum;
        if (ratio >= 1.0) {
            return BAD_RGB;
        }
        if (ratio >= 0.75) {
            return WARN_RGB;
        }
        return VALUE_RGB;
    }

    private static int stateColor(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (
            normalized.contains("ERROR")
                || normalized.contains("FAILED")
                || normalized.contains("UNHEALTH")
                || normalized.contains("STOPPED")
        ) {
            return BAD_RGB;
        }
        if (
            normalized.contains("WARN")
                || normalized.contains("DEGRADED")
        ) {
            return WARN_RGB;
        }
        if (
            normalized.contains("RUNNING")
                || normalized.contains("HEALTH")
                || normalized.contains("OK")
                || normalized.contains("READY")
        ) {
            return GOOD_RGB;
        }
        return VALUE_RGB;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        if (value < 1024.0) {
            return String.format(
                Locale.ROOT,
                "%.1f KiB",
                value
            );
        }
        value /= 1024.0;
        return String.format(
            Locale.ROOT,
            "%.1f MiB",
            value
        );
    }

    public enum Kind {
        TITLE,
        SECTION,
        FIELD
    }

    public record StatusLine(
        Kind kind,
        String label,
        String value,
        int valueRgb
    ) {
        public StatusLine {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
        }

        static StatusLine title(String text) {
            return new StatusLine(
                Kind.TITLE,
                text,
                "",
                TITLE_RGB
            );
        }

        static StatusLine section(String text) {
            return new StatusLine(
                Kind.SECTION,
                text,
                "",
                SECTION_RGB
            );
        }

        static StatusLine field(
            String label,
            String value,
            int valueRgb
        ) {
            return new StatusLine(
                Kind.FIELD,
                label,
                value,
                valueRgb
            );
        }

        public String plainText() {
            return switch (kind) {
                case TITLE -> "━━ " + label + " ━━";
                case SECTION -> "  [" + label + "]";
                case FIELD -> "  " + label + ": " + value;
            };
        }
    }
}
