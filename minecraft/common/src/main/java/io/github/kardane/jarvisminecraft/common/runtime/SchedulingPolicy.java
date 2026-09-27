package io.github.kardane.jarvisminecraft.common.runtime;

import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;
import io.github.kardane.jarvisminecraft.common.protocol.ProtocolException;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ScheduleActionArguments;

import java.util.Objects;
import java.util.Set;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class SchedulingPolicy {
    private static final Set<ToolName> SCHEDULABLE = Set.of(
        ToolName.TELEPORT_STAFF,
        ToolName.WEATHER_SET,
        ToolName.TIME_SET
    );

    private final ConfigManager configManager;

    public SchedulingPolicy(ConfigManager configManager) {
        this.configManager = Objects.requireNonNull(
            configManager,
            "configManager"
        );
    }

    public static SchedulingPolicy defaults() {
        return new SchedulingPolicy(
            new ConfigManager(JarvisConfig::defaults)
        );
    }

    public boolean schedulingEnabled() {
        return configManager.current().scheduling().enabled();
    }

    public boolean schedulable(ToolName tool) {
        return SCHEDULABLE.contains(tool);
    }

    public void validateRegistration(
        ScheduleActionArguments arguments
    ) {
        Objects.requireNonNull(arguments, "arguments");
        JarvisConfig.Scheduling config =
            configManager.current().scheduling();

        if (!config.enabled()) {
            throw new ProtocolException(
                ErrorCode.UNSUPPORTED,
                "Scheduling is disabled."
            );
        }
        if (!schedulable(arguments.tool())) {
            throw new ProtocolException(
                ErrorCode.UNSUPPORTED,
                "Tool is not schedulable."
            );
        }
        if (
            arguments.delaySeconds() < 1
                || arguments.delaySeconds()
                    > config.maxDelaySeconds()
        ) {
            throw new ProtocolException(
                ErrorCode.INVALID_ARGUMENT,
                "Scheduled delay exceeds current policy."
            );
        }

        Integer interval = arguments.intervalSeconds();
        Integer duration = arguments.durationSeconds();
        if ((interval == null) != (duration == null)) {
            throw new ProtocolException(
                ErrorCode.INVALID_ARGUMENT,
                "intervalSeconds and durationSeconds must both be null or both be set."
            );
        }
        if (interval == null) {
            return;
        }
        if (
            interval < 1
                || duration < interval
                || duration > config.maxDurationSeconds()
        ) {
            throw new ProtocolException(
                ErrorCode.INVALID_ARGUMENT,
                "Repeating schedule exceeds current policy."
            );
        }
    }

    public boolean stillAllowed(
        ScheduleActionArguments arguments
    ) {
        JarvisConfig.Scheduling config =
            configManager.current().scheduling();
        if (!config.enabled()) {
            return false;
        }
        if (
            arguments.delaySeconds()
                > config.maxDelaySeconds()
        ) {
            return false;
        }
        return arguments.durationSeconds() == null
            || arguments.durationSeconds()
                <= config.maxDurationSeconds();
    }
}
