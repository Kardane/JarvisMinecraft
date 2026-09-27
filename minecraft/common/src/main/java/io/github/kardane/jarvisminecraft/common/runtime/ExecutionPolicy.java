package io.github.kardane.jarvisminecraft.common.runtime;

import io.github.kardane.jarvisminecraft.common.config.ConfigManager;
import io.github.kardane.jarvisminecraft.common.config.JarvisConfig;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.Risk;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class ExecutionPolicy {
    private final ConfigManager configManager;

    public ExecutionPolicy(ConfigManager configManager) {
        this.configManager = Objects.requireNonNull(
            configManager,
            "configManager"
        );
    }

    public static ExecutionPolicy defaults() {
        return new ExecutionPolicy(
            new ConfigManager(JarvisConfig::defaults)
        );
    }

    public Set<ToolName> filter(
        Set<ToolName> candidates,
        boolean requesterToolAuthority,
        String interactionOrigin
    ) {
        Objects.requireNonNull(candidates, "candidates");
        EnumSet<ToolName> allowed =
            EnumSet.noneOf(ToolName.class);

        JarvisConfig current = configManager.current();
        JarvisConfig.Execution config = current.execution();

        for (ToolName tool : candidates) {
            if (
                allows(
                    current,
                    tool,
                    requesterToolAuthority,
                    interactionOrigin
                )
            ) {
                allowed.add(tool);
            }
        }
        return Set.copyOf(allowed);
    }

    public boolean allows(
        ToolName tool,
        boolean requesterToolAuthority,
        String interactionOrigin
    ) {
        return allows(
            configManager.current(),
            Objects.requireNonNull(tool, "tool"),
            requesterToolAuthority,
            interactionOrigin
        );
    }

    private boolean allows(
        JarvisConfig current,
        ToolName tool,
        boolean requesterToolAuthority,
        String interactionOrigin
    ) {
        Objects.requireNonNull(current, "current");
        JarvisConfig.Execution config = current.execution();
        Objects.requireNonNull(
            interactionOrigin,
            "interactionOrigin"
        );

        if (!requesterToolAuthority) {
            return false;
        }

        if (
            isProactive(interactionOrigin)
                && tool.stateChanging()
        ) {
            return false;
        }

        if (tool == ToolName.SCHEDULE_ACTION) {
            return current.scheduling().enabled()
                && config.mode()
                    != JarvisConfig.ExecutionMode.READ_TALK
                && !isProactive(interactionOrigin);
        }
        if (
            tool
                == ToolName.CANCEL_SCHEDULED_ACTION
        ) {
            return true;
        }

        JarvisConfig.ToolFilter filter = switch (config.mode()) {
            case READ_TALK -> null;
            case EXECUTE_LITE -> config.lite();
            case EXECUTE -> config.full();
        };

        if (
            filter != null
                && contains(filter.denyTools(), tool)
        ) {
            return false;
        }

        if (!tool.stateChanging()) {
            return true;
        }

        if (isProactive(interactionOrigin)) {
            return false;
        }

        return switch (config.mode()) {
            case READ_TALK -> false;
            case EXECUTE_LITE ->
                tool.risk() == Risk.LOW
                    && contains(
                        config.lite().allowTools(),
                        tool
                    );
            case EXECUTE ->
                contains(
                    config.full().allowTools(),
                    tool
                );
        };
    }

    private boolean isProactive(String origin) {
        return "PROACTIVE".equalsIgnoreCase(origin)
            || "PROACTIVE_CANDIDATE".equalsIgnoreCase(origin);
    }

    private boolean contains(
        java.util.List<String> values,
        ToolName tool
    ) {
        return values.contains(tool.wireName());
    }
}
