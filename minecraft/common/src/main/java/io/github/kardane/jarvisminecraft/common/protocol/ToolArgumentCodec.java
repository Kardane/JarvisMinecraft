package io.github.kardane.jarvisminecraft.common.protocol;

import com.google.gson.JsonObject;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolArguments;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

/**
 * Backward-compatible parser facade over the centralized {@link ToolSpec}.
 */
public final class ToolArgumentCodec {
    public ToolArguments parse(
        ToolName tool,
        JsonObject arguments
    ) {
        return ToolSpec.parse(tool, arguments);
    }
}
