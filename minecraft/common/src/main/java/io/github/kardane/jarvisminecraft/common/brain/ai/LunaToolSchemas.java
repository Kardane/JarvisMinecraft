package io.github.kardane.jarvisminecraft.common.brain.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kardane.jarvisminecraft.common.protocol.ToolArgumentCodec;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.ToolArguments;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

public final class LunaToolSchemas {
    private static final String UUID_PATTERN =
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final ToolArgumentCodec argumentCodec;

    public LunaToolSchemas(ToolArgumentCodec argumentCodec) {
        this.argumentCodec = Objects.requireNonNull(argumentCodec, "argumentCodec");
    }

    public List<Definition> definitions(Set<ToolName> activeTools) {
        Objects.requireNonNull(activeTools, "activeTools");
        List<Definition> output = new ArrayList<>();
        for (ToolName tool : ToolName.values()) {
            if (activeTools.contains(tool)) {
                output.addAll(definitionsFor(tool));
            }
        }
        return List.copyOf(output);
    }

    public LunaStep.ToolCall translate(
        String functionName,
        String argumentsJson,
        Set<ToolName> activeTools
    ) {
        Objects.requireNonNull(functionName, "functionName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        Objects.requireNonNull(activeTools, "activeTools");

        Definition definition = definitionByAiName(functionName);
        if (!activeTools.contains(definition.coreTool())) {
            throw new IllegalArgumentException("Luna requested an inactive Tool.");
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(argumentsJson);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Luna returned invalid Tool JSON.", failure);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Luna Tool arguments must be an object.");
        }

        ToolArguments arguments =
            argumentCodec.parse(definition.coreTool(), parsed.getAsJsonObject());
        return new LunaStep.ToolCall(definition.coreTool(), arguments);
    }

    public ToolName coreToolForAiName(String functionName) {
        return definitionByAiName(functionName).coreTool();
    }

    private List<Definition> definitionsFor(ToolName tool) {
        return switch (tool) {
            case GET_SERVER_STATUS -> List.of(
                define(
                    tool.wireName(),
                    tool,
                    "Read current server performance/status metrics.",
                    objectSchema(new JsonObject(), List.of())
                )
            );
            case GET_ONLINE_PLAYERS -> List.of(
                define(
                    tool.wireName(),
                    tool,
                    "List currently online players using bounded pagination.",
                    objectSchema(
                        properties(
                            "cursor", nullableStringSchema(256),
                            "limit", integerSchema(1, 100)
                        ),
                        List.of("cursor", "limit")
                    )
                )
            );
            case GET_PLAYER -> List.of(
                define(
                    "get_player_by_uuid",
                    tool,
                    "Resolve exactly one current player by UUID.",
                    objectSchema(
                        properties("playerUuid", uuidSchema()),
                        List.of("playerUuid")
                    )
                ),
                define(
                    "get_player_by_name",
                    tool,
                    "Resolve exactly one current player by exact Minecraft name. Do not use fuzzy names.",
                    objectSchema(
                        properties("exactName", stringSchema(1, 16)),
                        List.of("exactName")
                    )
                )
            );
            case GET_PLAYER_LOCATION -> List.of(
                uuidTool(tool, "Read the current location of one online player.")
            );
            case GET_CMI_PLAYER_INFO -> List.of(
                uuidTool(tool, "Read an online player's CMI nickname and AFK state.")
            );
            case GET_NEARBY_PLAYERS -> List.of(
                define(
                    tool.wireName(),
                    tool,
                    "List online players near a known server location.",
                    objectSchema(
                        properties(
                            "center", locationSchema(),
                            "radius", numberSchemaExclusiveMin(0, 64),
                            "limit", integerSchema(1, 100)
                        ),
                        List.of("center", "radius", "limit")
                    )
                )
            );
            case GET_WORLD_INFO -> List.of(
                define(
                    tool.wireName(),
                    tool,
                    "Read supported information for a loaded world.",
                    objectSchema(
                        properties("worldId", stringSchema(1, 128)),
                        List.of("worldId")
                    )
                )
            );
            case TELEPORT_STAFF -> List.of(
                define(
                    tool.wireName(),
                    tool,
                    "Teleport only the requesting operator to an online target player. Use only after an explicit move request.",
                    objectSchema(
                        properties("targetPlayerUuid", uuidSchema()),
                        List.of("targetPlayerUuid")
                    )
                )
            );
            case LOOKUP_AREA_HISTORY -> List.of(
                define(
                    tool.wireName(),
                    tool,
                    "Read bounded CoreProtect history around a known location.",
                    objectSchema(
                        properties(
                            "center", locationSchema(),
                            "radius", integerSchema(0, 64),
                            "lookbackSeconds", integerSchema(1, 86_400),
                            "cursor", nullableStringSchema(256),
                            "limit", integerSchema(1, 100)
                        ),
                        List.of("center", "radius", "lookbackSeconds", "cursor", "limit")
                    )
                )
            );
            case LOOKUP_PLAYER_HISTORY -> List.of(
                define(
                    tool.wireName(),
                    tool,
                    "Read bounded CoreProtect history for one player.",
                    objectSchema(
                        properties(
                            "playerUuid", uuidSchema(),
                            "lookbackSeconds", integerSchema(1, 86_400),
                            "cursor", nullableStringSchema(256),
                            "limit", integerSchema(1, 100)
                        ),
                        List.of("playerUuid", "lookbackSeconds", "cursor", "limit")
                    )
                )
            );
            case GET_REGIONS_AT_LOCATION -> List.of(
                define(
                    tool.wireName(),
                    tool,
                    "Read WorldGuard regions containing a known location.",
                    objectSchema(
                        properties("location", locationSchema()),
                        List.of("location")
                    )
                )
            );
            case GET_REGION_INFO -> List.of(
                define(
                    tool.wireName(),
                    tool,
                    "Read one exact WorldGuard region by world and region id.",
                    objectSchema(
                        properties(
                            "worldId", stringSchema(1, 128),
                            "regionId", stringSchema(1, 128)
                        ),
                        List.of("worldId", "regionId")
                    )
                )
            );
            case CHECK_BUILD_PERMISSION -> List.of(
                define(
                    tool.wireName(),
                    tool,
                    "Ask WorldGuard whether one player may build at a location.",
                    objectSchema(
                        properties(
                            "playerUuid", uuidSchema(),
                            "location", locationSchema()
                        ),
                        List.of("playerUuid", "location")
                    )
                )
            );
        };
    }

    private Definition uuidTool(ToolName tool, String description) {
        return define(
            tool.wireName(),
            tool,
            description,
            objectSchema(
                properties("playerUuid", uuidSchema()),
                List.of("playerUuid")
            )
        );
    }

    private Definition definitionByAiName(String name) {
        for (ToolName tool : ToolName.values()) {
            for (Definition definition : definitionsFor(tool)) {
                if (definition.name().equals(name)) {
                    return definition;
                }
            }
        }
        throw new IllegalArgumentException("Luna requested an unknown function Tool.");
    }

    private Definition define(
        String name,
        ToolName coreTool,
        String description,
        JsonObject parameters
    ) {
        return new Definition(name, coreTool, description, parameters);
    }

    private JsonObject locationSchema() {
        return objectSchema(
            properties(
                "worldId", stringSchema(1, 128),
                "x", numberSchema(-30_000_000, 30_000_000),
                "y", numberSchema(-2_048, 4_096),
                "z", numberSchema(-30_000_000, 30_000_000),
                "yaw", numberSchema(-360, 360),
                "pitch", numberSchema(-90, 90)
            ),
            List.of("worldId", "x", "y", "z", "yaw", "pitch")
        );
    }

    private JsonObject objectSchema(JsonObject properties, List<String> required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        schema.add("properties", properties);
        JsonArray requiredArray = new JsonArray();
        required.forEach(requiredArray::add);
        schema.add("required", requiredArray);
        return schema;
    }

    private JsonObject properties(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Schema property pairs must be even.");
        }
        JsonObject properties = new JsonObject();
        for (int index = 0; index < pairs.length; index += 2) {
            properties.add((String) pairs[index], (JsonElement) pairs[index + 1]);
        }
        return properties;
    }

    private JsonObject uuidSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("pattern", UUID_PATTERN);
        return schema;
    }

    private JsonObject stringSchema(int min, int max) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("minLength", min);
        schema.addProperty("maxLength", max);
        return schema;
    }

    private JsonObject nullableStringSchema(int max) {
        JsonObject schema = new JsonObject();
        JsonArray types = new JsonArray();
        types.add("string");
        types.add("null");
        schema.add("type", types);
        schema.addProperty("maxLength", max);
        return schema;
    }

    private JsonObject integerSchema(int min, int max) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("minimum", min);
        schema.addProperty("maximum", max);
        return schema;
    }

    private JsonObject numberSchema(double min, double max) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "number");
        schema.addProperty("minimum", min);
        schema.addProperty("maximum", max);
        return schema;
    }

    private JsonObject numberSchemaExclusiveMin(double min, double max) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "number");
        schema.addProperty("exclusiveMinimum", min);
        schema.addProperty("maximum", max);
        return schema;
    }

    public record Definition(
        String name,
        ToolName coreTool,
        String description,
        JsonObject parameters
    ) {
        public Definition {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(coreTool, "coreTool");
            Objects.requireNonNull(description, "description");
            parameters = Objects.requireNonNull(parameters, "parameters").deepCopy();
        }

        @Override
        public JsonObject parameters() {
            return parameters.deepCopy();
        }
    }
}
