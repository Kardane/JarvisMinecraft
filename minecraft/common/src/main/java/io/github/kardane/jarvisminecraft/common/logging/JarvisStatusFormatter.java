package io.github.kardane.jarvisminecraft.common.logging;

import io.github.kardane.jarvisminecraft.common.brain.BrainGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class JarvisStatusFormatter {
    private JarvisStatusFormatter() {
    }

    public static List<String> lines(
        BrainGateway.StatusSnapshot status
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("JARVIS status");
        lines.add("Runtime: " + status.runtime());
        lines.add("Interaction: " + status.interactionMode());
        lines.add("Audience: " + status.audienceMode());
        lines.add("Execution: " + status.executionMode());
        lines.add(
            "Scheduling: "
                + (status.schedulingEnabled() ? "enabled" : "disabled")
        );
        lines.add(
            "AI Queue: "
                + status.aiQueued()
                + " / "
                + status.aiQueueCapacity()
        );
        lines.add(
            "AI Active: "
                + status.aiActive()
                + " / "
                + status.aiConcurrentCapacity()
        );
        lines.add(
            "Proactive In-flight: "
                + status.proactiveInFlight()
        );

        BrainGateway.AuditHealth audit = status.audit();
        lines.add("Audit: " + audit.status());
        lines.add(
            "Audit Queue: "
                + audit.queueDepth()
                + " / "
                + audit.maxQueue()
        );
        lines.add("Audit Writable: " + audit.writable());
        lines.add("Audit Rejected: " + audit.rejectedRecords());
        lines.add("Audit Files: " + audit.fileCount());
        lines.add("Audit Size: " + humanBytes(audit.totalBytes()));
        if (audit.lastSuccessfulWriteAt() != null) {
            lines.add(
                "Audit Last Write: "
                    + audit.lastSuccessfulWriteAt()
            );
        }
        if (audit.lastErrorCode() != null) {
            lines.add(
                "Audit Last Error: "
                    + audit.lastErrorCode()
            );
        }
        return List.copyOf(lines);
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
}
