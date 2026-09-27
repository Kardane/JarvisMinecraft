package io.github.kardane.jarvisminecraft.common.brain.ai;

import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolArguments;

import java.util.List;
import java.util.Objects;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public sealed interface LunaStep permits LunaStep.Final, LunaStep.Tools {
    Usage usage();

    record Final(
        String text,
        SessionState sessionState,
        Usage usage
    ) implements LunaStep {
        public Final(
            String text,
            SessionState sessionState
        ) {
            this(
                text,
                sessionState,
                Usage.unavailable()
            );
        }

        public Final {
            text = Objects.requireNonNull(text, "text");
            Objects.requireNonNull(sessionState, "sessionState");
            Objects.requireNonNull(usage, "usage");
        }
    }

    record Tools(
        List<ToolCall> calls,
        Usage usage
    ) implements LunaStep {
        public Tools(List<ToolCall> calls) {
            this(calls, Usage.unavailable());
        }

        public Tools {
            calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
            Objects.requireNonNull(usage, "usage");
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

    record Usage(
        long inputTokens,
        long outputTokens,
        long totalTokens,
        boolean complete
    ) {
        public Usage {
            if (
                inputTokens < 0
                    || outputTokens < 0
                    || totalTokens < 0
            ) {
                throw new IllegalArgumentException(
                    "Token usage values must be non-negative."
                );
            }
        }

        public static Usage zero() {
            return new Usage(0L, 0L, 0L, true);
        }

        public static Usage unavailable() {
            return new Usage(0L, 0L, 0L, false);
        }

        public Usage plus(Usage other) {
            Objects.requireNonNull(other, "other");
            return new Usage(
                Math.addExact(inputTokens, other.inputTokens),
                Math.addExact(outputTokens, other.outputTokens),
                Math.addExact(totalTokens, other.totalTokens),
                complete && other.complete
            );
        }
    }

    enum SessionState {
        CONTINUE,
        END
    }
}
