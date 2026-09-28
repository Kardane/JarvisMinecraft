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
                evaluate(
                    current,
                    tool,
                    requesterToolAuthority,
                    interactionOrigin
                ).allowed()
            ) {
                allowed.add(tool);
            }
        }
        return Set.copyOf(allowed);
    }

    public JarvisConfig.ExecutionMode currentMode() {
        return configManager.current().execution().mode();
    }

    public boolean allows(
        ToolName tool,
        boolean requesterToolAuthority,
        String interactionOrigin
    ) {
        return evaluate(
            Objects.requireNonNull(tool, "tool"),
            requesterToolAuthority,
            interactionOrigin
        ).allowed();
    }

    public Decision evaluate(
        ToolName tool,
        boolean requesterToolAuthority,
        String interactionOrigin
    ) {
        return evaluate(
            configManager.current(),
            Objects.requireNonNull(tool, "tool"),
            requesterToolAuthority,
            interactionOrigin
        );
    }

    private Decision evaluate(
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
            return Decision.denied(
                DenialReason.NO_REQUESTER_AUTHORITY,
                config.mode()
            );
        }

        if (
            isProactive(interactionOrigin)
                && tool.stateChanging()
        ) {
            return Decision.denied(
                DenialReason.PROACTIVE_MUTATION_BLOCK,
                config.mode()
            );
        }

        if (tool == ToolName.RUN_COMMAND) {
            return Decision.allowed(config.mode());
        }

        if (tool == ToolName.SCHEDULE_ACTION) {
            if (
                config.mode()
                    == JarvisConfig.ExecutionMode.READ_TALK
            ) {
                return Decision.denied(
                    DenialReason.READ_TALK,
                    config.mode()
                );
            }
            return current.scheduling().enabled()
                ? Decision.allowed(config.mode())
                : Decision.denied(
                    DenialReason.NOT_ALLOWLISTED,
                    config.mode()
                );
        }
        if (
            tool
                == ToolName.CANCEL_SCHEDULED_ACTION
        ) {
            return Decision.allowed(config.mode());
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
            return Decision.denied(
                DenialReason.DENYLISTED,
                config.mode()
            );
        }

        if (!tool.stateChanging()) {
            return Decision.allowed(config.mode());
        }

        return switch (config.mode()) {
            case READ_TALK -> Decision.denied(
                DenialReason.READ_TALK,
                config.mode()
            );
            case EXECUTE_LITE ->
                tool.risk() == Risk.LOW
                    && contains(
                        config.lite().allowTools(),
                        tool
                    )
                    ? Decision.allowed(config.mode())
                    : Decision.denied(
                        DenialReason.NOT_ALLOWLISTED,
                        config.mode()
                    );
            case EXECUTE ->
                contains(
                    config.full().allowTools(),
                    tool
                )
                    ? Decision.allowed(config.mode())
                    : Decision.denied(
                        DenialReason.NOT_ALLOWLISTED,
                        config.mode()
                    );
        };
    }

    public enum DenialReason {
        NO_REQUESTER_AUTHORITY,
        READ_TALK,
        NOT_ALLOWLISTED,
        DENYLISTED,
        PROACTIVE_MUTATION_BLOCK,
        POLICY_CHANGED,
        TOOL_INACTIVE
    }

    public record Decision(
        boolean allowed,
        DenialReason reason,
        JarvisConfig.ExecutionMode executionMode
    ) {
        public Decision {
            Objects.requireNonNull(executionMode, "executionMode");
            if (allowed && reason != null) {
                throw new IllegalArgumentException(
                    "Allowed execution decision must not have a denial reason."
                );
            }
            if (!allowed && reason == null) {
                throw new IllegalArgumentException(
                    "Denied execution decision requires a denial reason."
                );
            }
        }

        public static Decision allowed(
            JarvisConfig.ExecutionMode executionMode
        ) {
            return new Decision(true, null, executionMode);
        }

        public static Decision denied(
            DenialReason reason,
            JarvisConfig.ExecutionMode executionMode
        ) {
            return new Decision(
                false,
                Objects.requireNonNull(reason, "reason"),
                executionMode
            );
        }
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
