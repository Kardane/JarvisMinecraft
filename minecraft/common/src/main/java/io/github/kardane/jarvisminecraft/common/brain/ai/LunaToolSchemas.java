package io.github.kardane.jarvisminecraft.common.brain.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kardane.jarvisminecraft.common.protocol.ToolArgumentCodec;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolArguments;
import io.github.kardane.jarvisminecraft.common.protocol.ToolSpec;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class LunaToolSchemas {
    private final ToolArgumentCodec argumentCodec;

    public LunaToolSchemas(ToolArgumentCodec argumentCodec) {
        this.argumentCodec = Objects.requireNonNull(
            argumentCodec,
            "argumentCodec"
        );
    }

    public List<Definition> definitions(
        Set<ToolName> activeTools
    ) {
        return ToolSpec.definitions(activeTools)
            .stream()
            .map(Definition::from)
            .toList();
    }

    public LunaStep.ToolCall translate(
        String functionName,
        String argumentsJson,
        Set<ToolName> activeTools
    ) {
        Objects.requireNonNull(
            functionName,
            "functionName"
        );
        Objects.requireNonNull(
            argumentsJson,
            "argumentsJson"
        );
        Objects.requireNonNull(
            activeTools,
            "activeTools"
        );

        ToolSpec.AiDefinition definition =
            ToolSpec.definitionByAiName(functionName);
        if (!activeTools.contains(definition.coreTool())) {
            throw new IllegalArgumentException(
                "Luna requested an inactive Tool."
            );
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(
                argumentsJson
            );
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                "Luna returned invalid Tool JSON.",
                failure
            );
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException(
                "Luna Tool arguments must be an object."
            );
        }

        ToolArguments arguments = argumentCodec.parse(
            definition.coreTool(),
            parsed.getAsJsonObject()
        );
        return new LunaStep.ToolCall(
            definition.coreTool(),
            arguments
        );
    }

    public ToolName coreToolForAiName(
        String functionName
    ) {
        return ToolSpec
            .definitionByAiName(functionName)
            .coreTool();
    }

    public record Definition(
        String name,
        ToolName coreTool,
        String description,
        JsonObject parameters
    ) {
        public Definition {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(
                coreTool,
                "coreTool"
            );
            Objects.requireNonNull(
                description,
                "description"
            );
            parameters = Objects.requireNonNull(
                parameters,
                "parameters"
            ).deepCopy();
        }

        private static Definition from(
            ToolSpec.AiDefinition definition
        ) {
            return new Definition(
                definition.name(),
                definition.coreTool(),
                definition.description(),
                definition.parameters()
            );
        }

        @Override
        public JsonObject parameters() {
            return parameters.deepCopy();
        }
    }
}
