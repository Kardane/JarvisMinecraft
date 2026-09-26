package io.github.kardane.jarvisminecraft.common.brain.ai;

import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolArguments;

import java.util.List;
import java.util.Objects;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public sealed interface LunaStep permits LunaStep.Final, LunaStep.Tools {
    record Final(String text, SessionState sessionState) implements LunaStep {
        public Final {
            text = Objects.requireNonNull(text, "text");
            Objects.requireNonNull(sessionState, "sessionState");
        }
    }

    record Tools(List<ToolCall> calls) implements LunaStep {
        public Tools {
            calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
            if (calls.isEmpty()) {
                throw new IllegalArgumentException("Luna Tool step must contain calls.");
            }
        }
    }

    record ToolCall(ToolName tool, ToolArguments arguments) {
        public ToolCall {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    enum SessionState {
        CONTINUE,
        END
    }
}
