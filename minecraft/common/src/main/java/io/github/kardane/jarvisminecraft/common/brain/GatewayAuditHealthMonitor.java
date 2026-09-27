package io.github.kardane.jarvisminecraft.common.brain;

import io.github.kardane.jarvisminecraft.common.audit.AsyncJsonlAuditSink;
import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.logging.JarvisEvents;
import io.github.kardane.jarvisminecraft.common.logging.JarvisFields;
import io.github.kardane.jarvisminecraft.common.logging.JarvisLog;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class GatewayAuditHealthMonitor {
    private final ConfigManager configManager;
    private final AsyncJsonlAuditSink audit;
    private final Executor executor;
    private final BooleanSupplier running;
    private final JarvisLog log;

    private volatile AsyncJsonlAuditSink.Status lastStatus;
    private volatile String lastErrorCode;

    GatewayAuditHealthMonitor(
        ConfigManager configManager,
        AsyncJsonlAuditSink audit,
        Executor executor,
        BooleanSupplier running,
        JarvisLog log
    ) {
        this.configManager = Objects.requireNonNull(
            configManager,
            "configManager"
        );
        this.audit = audit;
        this.executor = executor;
        this.running = Objects.requireNonNull(
            running,
            "running"
        );
        this.log = Objects.requireNonNull(log, "log");
    }

    void start() {
        schedulePoll();
    }

    BrainGateway.AuditHealth snapshot() {
        return audit == null
            ? BrainGateway.AuditHealth.unavailable()
            : toAuditHealth(audit.health());
    }

    private void schedulePoll() {
        if (audit == null || executor == null) {
            return;
        }

        int intervalSeconds =
            configManager.current()
                .logging()
                .healthIntervalSeconds();
        try {
            CompletableFuture.delayedExecutor(
                intervalSeconds,
                TimeUnit.SECONDS,
                executor
            ).execute(() -> {
                if (!running.getAsBoolean()) {
                    return;
                }
                report();
                schedulePoll();
            });
        } catch (RuntimeException ignored) {
            // Operational health reporting must not change runtime behavior.
        }
    }

    private void report() {
        AsyncJsonlAuditSink.Health health = audit.health();
        AsyncJsonlAuditSink.Status previous = lastStatus;
        String previousError = lastErrorCode;
        lastStatus = health.status();
        lastErrorCode = health.lastErrorCode();

        if (
            previous == health.status()
                && Objects.equals(
                    previousError,
                    health.lastErrorCode()
                )
        ) {
            return;
        }

        Map<String, Object> fields = JarvisFields.of(
            "queueDepth", health.queueDepth(),
            "maxQueue", health.maxQueue(),
            "rejectedRecords", health.rejectedRecords(),
            "writable", health.writable(),
            "lastErrorCode", health.lastErrorCode()
        );

        switch (health.status()) {
            case HEALTHY -> {
                if (
                    previous != null
                        && previous
                            != AsyncJsonlAuditSink.Status.HEALTHY
                ) {
                    log.info(
                        JarvisEvents.AUDIT_RECOVERED,
                        fields
                    );
                }
            }
            case DEGRADED -> log.warn(
                JarvisEvents.AUDIT_DEGRADED,
                fields
            );
            case UNHEALTHY -> log.error(
                JarvisEvents.AUDIT_UNHEALTHY,
                null,
                fields
            );
        }
    }

    private BrainGateway.AuditHealth toAuditHealth(
        AsyncJsonlAuditSink.Health health
    ) {
        return new BrainGateway.AuditHealth(
            health.status().name(),
            health.writable(),
            health.closed(),
            health.queueDepth(),
            health.maxQueue(),
            health.rejectedRecords(),
            health.lastSuccessfulWriteAt(),
            health.lastErrorAt(),
            health.lastErrorCode(),
            health.totalBytes(),
            health.fileCount()
        );
    }
}
