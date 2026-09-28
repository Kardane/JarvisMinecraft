package io.github.kardane.jarvisminecraft.common.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.kardane.jarvisminecraft.common.protocol.ToolModels.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;

/**
 * Single source of truth for Tool argument shapes, validation rules and AI-facing schemas.
 */
public final class ToolSpec {
    private static final String UUID_PATTERN =
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private static final ObjectShape<Location> LOCATION = shape(
        values -> new Location(
            values.get("worldId"),
            values.get("x"),
            values.get("y"),
            values.get("z"),
            values.get("yaw"),
            values.get("pitch")
        ),
        field("worldId", stringRule(1, 128)),
        field("x", numberRule(-30_000_000, 30_000_000)),
        field("y", numberRule(-2_048, 4_096)),
        field("z", numberRule(-30_000_000, 30_000_000)),
        field("yaw", numberRule(-360, 360)),
        field("pitch", numberRule(-90, 90))
    );

    private static final EnumMap<ToolName, List<Variant>> VARIANTS =
        new EnumMap<>(ToolName.class);
    private static final Map<String, Variant> AI_VARIANTS;

    static {
        register(
            ToolName.GET_SERVER_STATUS,
            variant(
                ToolName.GET_SERVER_STATUS.wireName(),
                ToolName.GET_SERVER_STATUS,
                "Read current server performance/status metrics.",
                shape(values -> new NoArguments())
            )
        );

        register(
            ToolName.GET_ONLINE_PLAYERS,
            variant(
                ToolName.GET_ONLINE_PLAYERS.wireName(),
                ToolName.GET_ONLINE_PLAYERS,
                "List currently online players using bounded pagination.",
                shape(
                    values -> new PagingArguments(
                        values.get("cursor"),
                        values.get("limit")
                    ),
                    field("cursor", nullableStringRule(256)),
                    field("limit", integerRule(1, 100))
                )
            )
        );

        register(
            ToolName.GET_PLAYER,
            variant(
                "get_player_by_uuid",
                ToolName.GET_PLAYER,
                "Resolve exactly one current player by UUID.",
                shape(
                    values -> new GetPlayerByUuidArguments(values.get("playerUuid")),
                    field("playerUuid", uuidRule())
                ),
                null,
                "playerUuid"
            ),
            variant(
                "get_player_by_name",
                ToolName.GET_PLAYER,
                "Resolve exactly one current player by exact Minecraft name. Do not use fuzzy names.",
                shape(
                    values -> new GetPlayerByNameArguments(values.get("exactName")),
                    field("exactName", stringRule(1, 16))
                ),
                null,
                "exactName"
            )
        );

        register(
            ToolName.GET_PLAYER_LOCATION,
            uuidVariant(
                ToolName.GET_PLAYER_LOCATION,
                "Read the current location of one online player."
            )
        );
        register(
            ToolName.GET_CMI_PLAYER_INFO,
            uuidVariant(
                ToolName.GET_CMI_PLAYER_INFO,
                "Read an online player's CMI nickname and AFK state."
            )
        );

        register(
            ToolName.GET_NEARBY_PLAYERS,
            variant(
                ToolName.GET_NEARBY_PLAYERS.wireName(),
                ToolName.GET_NEARBY_PLAYERS,
                "List online players near a known server location.",
                shape(
                    values -> new NearbyArguments(
                        values.get("center"),
                        values.get("radius"),
                        values.get("limit")
                    ),
                    field("center", objectRule(LOCATION)),
                    field("radius", numberExclusiveMinRule(0, 64)),
                    field("limit", integerRule(1, 100))
                )
            )
        );

        register(
            ToolName.GET_WORLD_INFO,
            variant(
                ToolName.GET_WORLD_INFO.wireName(),
                ToolName.GET_WORLD_INFO,
                "Read supported information for a loaded world.",
                shape(
                    values -> new WorldInfoArguments(values.get("worldId")),
                    field("worldId", stringRule(1, 128))
                )
            )
        );

        ObjectShape<TeleportArguments> teleportShape = shape(
            values -> new TeleportArguments(values.get("targetPlayerUuid")),
            field("targetPlayerUuid", uuidRule())
        );
        register(
            ToolName.TELEPORT_STAFF,
            variant(
                ToolName.TELEPORT_STAFF.wireName(),
                ToolName.TELEPORT_STAFF,
                "Teleport only the requesting operator to an online target player. Use only after an explicit move request.",
                teleportShape
            )
        );

        ObjectShape<WeatherSetArguments> weatherShape = shape(
            values -> new WeatherSetArguments(
                values.get("worldId"),
                values.get("weather"),
                values.get("durationSeconds")
            ),
            field("worldId", stringRule(1, 128)),
            field("weather", enumRule(WeatherType.class)),
            field("durationSeconds", integerRule(1, 3_600))
        );
        register(
            ToolName.WEATHER_SET,
            variant(
                ToolName.WEATHER_SET.wireName(),
                ToolName.WEATHER_SET,
                "Set weather for one already loaded world after an explicit user request. Duration is 1 to 3600 seconds.",
                weatherShape
            )
        );

        ObjectShape<TimeSetArguments> timeShape = shape(
            values -> new TimeSetArguments(
                values.get("worldId"),
                values.get("timeOfDay")
            ),
            field("worldId", stringRule(1, 128)),
            field("timeOfDay", integerRule(0, 23_999))
        );
        register(
            ToolName.TIME_SET,
            variant(
                ToolName.TIME_SET.wireName(),
                ToolName.TIME_SET,
                "Set time-of-day for one already loaded world after an explicit user request. Value is 0 to 23999.",
                timeShape
            )
        );

        register(
            ToolName.RUN_COMMAND,
            variant(
                ToolName.RUN_COMMAND.wireName(),
                ToolName.RUN_COMMAND,
                "Execute one server command as console only when its command root is enabled in actions.properties. Use only for an explicit user-requested server action. Do not include a leading slash.",
                shape(
                    values -> new CommandArguments(
                        values.get("command")
                    ),
                    field("command", stringRule(1, 2_048))
                )
            )
        );

        register(
            ToolName.SCHEDULE_ACTION,
            scheduleVariant(
                "schedule_teleport_staff",
                ToolName.TELEPORT_STAFF,
                teleportShape
            ),
            scheduleVariant(
                "schedule_weather_set",
                ToolName.WEATHER_SET,
                weatherShape
            ),
            scheduleVariant(
                "schedule_time_set",
                ToolName.TIME_SET,
                timeShape
            )
        );

        register(
            ToolName.CANCEL_SCHEDULED_ACTION,
            variant(
                ToolName.CANCEL_SCHEDULED_ACTION.wireName(),
                ToolName.CANCEL_SCHEDULED_ACTION,
                "Cancel one pending scheduled action owned by the requesting player.",
                shape(
                    values -> new CancelScheduledActionArguments(values.get("scheduleId")),
                    field("scheduleId", uuidRule())
                )
            )
        );

        register(
            ToolName.LOOKUP_AREA_HISTORY,
            variant(
                ToolName.LOOKUP_AREA_HISTORY.wireName(),
                ToolName.LOOKUP_AREA_HISTORY,
                "Read bounded CoreProtect history around a known location.",
                shape(
                    values -> new AreaHistoryArguments(
                        values.get("center"),
                        values.get("radius"),
                        values.get("lookbackSeconds"),
                        values.get("cursor"),
                        values.get("limit")
                    ),
                    field("center", objectRule(LOCATION)),
                    field("radius", integerRule(0, 64)),
                    field("lookbackSeconds", integerRule(1, 86_400)),
                    field("cursor", nullableStringRule(256)),
                    field("limit", integerRule(1, 100))
                )
            )
        );

        register(
            ToolName.LOOKUP_PLAYER_HISTORY,
            variant(
                ToolName.LOOKUP_PLAYER_HISTORY.wireName(),
                ToolName.LOOKUP_PLAYER_HISTORY,
                "Read bounded CoreProtect history for one player.",
                shape(
                    values -> new PlayerHistoryArguments(
                        values.get("playerUuid"),
                        values.get("lookbackSeconds"),
                        values.get("cursor"),
                        values.get("limit")
                    ),
                    field("playerUuid", uuidRule()),
                    field("lookbackSeconds", integerRule(1, 86_400)),
                    field("cursor", nullableStringRule(256)),
                    field("limit", integerRule(1, 100))
                )
            )
        );

        register(
            ToolName.GET_REGIONS_AT_LOCATION,
            variant(
                ToolName.GET_REGIONS_AT_LOCATION.wireName(),
                ToolName.GET_REGIONS_AT_LOCATION,
                "Read WorldGuard regions containing a known location.",
                shape(
                    values -> new RegionsAtLocationArguments(values.get("location")),
                    field("location", objectRule(LOCATION))
                )
            )
        );

        register(
            ToolName.GET_REGION_INFO,
            variant(
                ToolName.GET_REGION_INFO.wireName(),
                ToolName.GET_REGION_INFO,
                "Read one exact WorldGuard region by world and region id.",
                shape(
                    values -> new RegionInfoArguments(
                        values.get("worldId"),
                        values.get("regionId")
                    ),
                    field("worldId", stringRule(1, 128)),
                    field("regionId", stringRule(1, 128))
                )
            )
        );

        register(
            ToolName.CHECK_BUILD_PERMISSION,
            variant(
                ToolName.CHECK_BUILD_PERMISSION.wireName(),
                ToolName.CHECK_BUILD_PERMISSION,
                "Ask WorldGuard whether one player may build at a location.",
                shape(
                    values -> new BuildPermissionArguments(
                        values.get("playerUuid"),
                        values.get("location")
                    ),
                    field("playerUuid", uuidRule()),
                    field("location", objectRule(LOCATION))
                )
            )
        );

        if (VARIANTS.size() != ToolName.values().length) {
            throw new IllegalStateException(
                "Every ToolName must have exactly one ToolSpec registration."
            );
        }

        Map<String, Variant> aiVariants = new LinkedHashMap<>();
        for (ToolName tool : ToolName.values()) {
            for (Variant variant : variants(tool)) {
                if (aiVariants.put(variant.aiName(), variant) != null) {
                    throw new IllegalStateException(
                        "Duplicate AI Tool name: " + variant.aiName()
                    );
                }
            }
        }
        AI_VARIANTS = Map.copyOf(aiVariants);
    }

    private ToolSpec() {
    }

    public static ToolArguments parse(ToolName tool, JsonObject arguments) {
        if (tool == null) {
            throw invalid("Tool must not be null.");
        }
        if (arguments == null) {
            throw invalid("Tool arguments must be an object.");
        }
        if (tool == ToolName.GET_PLAYER) {
            return parsePlayer(arguments);
        }
        if (tool == ToolName.SCHEDULE_ACTION) {
            return parseScheduled(arguments);
        }

        List<Variant> variants = variants(tool);
        if (variants.size() != 1) {
            throw new IllegalStateException(
                "Tool has ambiguous ToolSpec variants: " + tool
            );
        }
        return variants.getFirst().parse(arguments);
    }

    public static List<AiDefinition> definitions(Set<ToolName> activeTools) {
        Objects.requireNonNull(activeTools, "activeTools");
        List<AiDefinition> output = new ArrayList<>();
        for (ToolName tool : ToolName.values()) {
            if (activeTools.contains(tool)) {
                variants(tool).forEach(variant -> output.add(variant.definition()));
            }
        }
        return List.copyOf(output);
    }

    public static AiDefinition definitionByAiName(String functionName) {
        Objects.requireNonNull(functionName, "functionName");
        Variant variant = AI_VARIANTS.get(functionName);
        if (variant == null) {
            throw new IllegalArgumentException(
                "Luna requested an unknown function Tool."
            );
        }
        return variant.definition();
    }

    private static ToolArguments parsePlayer(JsonObject arguments) {
        if (arguments.size() != 1) {
            throw invalid("get_player requires exactly one selector.");
        }
        for (Variant variant : variants(ToolName.GET_PLAYER)) {
            if (arguments.has(variant.selectorField())) {
                return variant.parse(arguments);
            }
        }
        throw invalid("get_player selector is invalid.");
    }

    private static ToolArguments parseScheduled(JsonObject arguments) {
        JsonElement toolElement = arguments.get("tool");
        if (
            toolElement == null
                || !toolElement.isJsonPrimitive()
                || !toolElement.getAsJsonPrimitive().isString()
        ) {
            throw invalid("tool must be a string.");
        }

        String wireName = toolElement.getAsString();
        for (Variant variant : variants(ToolName.SCHEDULE_ACTION)) {
            if (
                variant.scheduledTool() != null
                    && variant.scheduledTool().wireName().equals(wireName)
            ) {
                return variant.parse(arguments);
            }
        }
        throw invalid("Scheduled Tool is not registered.");
    }

    private static Variant uuidVariant(ToolName tool, String description) {
        return variant(
            tool.wireName(),
            tool,
            description,
            shape(
                values -> new PlayerUuidArguments(values.get("playerUuid")),
                field("playerUuid", uuidRule())
            )
        );
    }

    private static Variant scheduleVariant(
        String aiName,
        ToolName nestedTool,
        ObjectShape<? extends ToolArguments> nestedShape
    ) {
        ObjectShape<ScheduleActionArguments> shape = shape(
            values -> {
                Integer interval = values.get("intervalSeconds");
                Integer duration = values.get("durationSeconds");
                if ((interval == null) != (duration == null)) {
                    throw invalid(
                        "intervalSeconds and durationSeconds must both be null or both be set."
                    );
                }
                if (interval != null && duration < interval) {
                    throw invalid(
                        "durationSeconds must be at least intervalSeconds."
                    );
                }
                return new ScheduleActionArguments(
                    nestedTool,
                    values.get("arguments"),
                    values.get("delaySeconds"),
                    interval,
                    duration
                );
            },
            field("tool", constStringRule(nestedTool.wireName())),
            field("arguments", objectRule(nestedShape)),
            field("delaySeconds", integerRule(1, 60)),
            field("intervalSeconds", nullableIntegerRule(1, 60)),
            field("durationSeconds", nullableIntegerRule(1, 60))
        );

        return variant(
            aiName,
            ToolName.SCHEDULE_ACTION,
            "Schedule the explicit structured action " + nestedTool.wireName()
                + ". delaySeconds is 1..60. For one-shot execution set intervalSeconds and durationSeconds to null. "
                + "For repetition set both to 1..60 and durationSeconds >= intervalSeconds.",
            shape,
            nestedTool,
            null
        );
    }

    private static Variant variant(
        String aiName,
        ToolName coreTool,
        String description,
        ObjectShape<? extends ToolArguments> shape
    ) {
        return variant(aiName, coreTool, description, shape, null, null);
    }

    private static Variant variant(
        String aiName,
        ToolName coreTool,
        String description,
        ObjectShape<? extends ToolArguments> shape,
        ToolName scheduledTool,
        String selectorField
    ) {
        return new Variant(
            aiName,
            coreTool,
            description,
            shape,
            scheduledTool,
            selectorField
        );
    }

    private static void register(ToolName tool, Variant... variants) {
        if (variants.length == 0) {
            throw new IllegalArgumentException(
                "ToolSpec registration requires at least one variant."
            );
        }
        if (VARIANTS.put(tool, List.of(variants)) != null) {
            throw new IllegalStateException(
                "Duplicate ToolSpec registration: " + tool
            );
        }
    }

    private static List<Variant> variants(ToolName tool) {
        List<Variant> variants = VARIANTS.get(tool);
        if (variants == null) {
            throw new IllegalStateException(
                "Missing ToolSpec registration: " + tool
            );
        }
        return variants;
    }

    @SafeVarargs
    private static <T> ObjectShape<T> shape(
        Function<Values, T> factory,
        Field<?>... fields
    ) {
        return new ObjectShape<>(List.of(fields), factory);
    }

    private static <T> Field<T> field(String name, ValueRule<T> rule) {
        return new Field<>(
            Objects.requireNonNull(name, "name"),
            Objects.requireNonNull(rule, "rule")
        );
    }

    private static ValueRule<String> stringRule(int min, int max) {
        return rule(
            (field, element) -> {
                if (
                    !element.isJsonPrimitive()
                        || !element.getAsJsonPrimitive().isString()
                ) {
                    throw invalid(field + " must be a string.");
                }
                String value = element.getAsString();
                if (value.length() < min || value.length() > max) {
                    throw invalid(field + " length is invalid.");
                }
                return value;
            },
            () -> {
                JsonObject schema = typeSchema("string");
                schema.addProperty("minLength", min);
                schema.addProperty("maxLength", max);
                return schema;
            }
        );
    }

    private static ValueRule<String> nullableStringRule(int max) {
        return rule(
            (field, element) -> {
                if (element.isJsonNull()) {
                    return null;
                }
                if (
                    !element.isJsonPrimitive()
                        || !element.getAsJsonPrimitive().isString()
                ) {
                    throw invalid(field + " must be string or null.");
                }
                String value = element.getAsString();
                if (value.length() > max) {
                    throw invalid(field + " is too long.");
                }
                return value;
            },
            () -> {
                JsonObject schema = nullableTypeSchema("string");
                schema.addProperty("maxLength", max);
                return schema;
            }
        );
    }

    private static ValueRule<UUID> uuidRule() {
        return rule(
            (field, element) -> {
                if (
                    element.isJsonNull()
                        || !element.isJsonPrimitive()
                        || !element.getAsJsonPrimitive().isString()
                ) {
                    throw invalid(field + " must be UUID string.");
                }
                try {
                    return UUID.fromString(element.getAsString());
                } catch (IllegalArgumentException failure) {
                    throw invalid(field + " is not a UUID.");
                }
            },
            () -> {
                JsonObject schema = typeSchema("string");
                schema.addProperty("pattern", UUID_PATTERN);
                return schema;
            }
        );
    }

    private static ValueRule<Integer> integerRule(int min, int max) {
        return rule(
            (field, element) -> {
                long value = primitiveLong(element, field);
                if (value < min || value > max) {
                    throw invalid(field + " out of range.");
                }
                return (int) value;
            },
            () -> {
                JsonObject schema = typeSchema("integer");
                schema.addProperty("minimum", min);
                schema.addProperty("maximum", max);
                return schema;
            }
        );
    }

    private static ValueRule<Integer> nullableIntegerRule(int min, int max) {
        return rule(
            (field, element) -> {
                if (element.isJsonNull()) {
                    return null;
                }
                long value = primitiveLong(element, field);
                if (value < min || value > max) {
                    throw invalid(field + " out of range.");
                }
                return (int) value;
            },
            () -> {
                JsonObject schema = nullableTypeSchema("integer");
                schema.addProperty("minimum", min);
                schema.addProperty("maximum", max);
                return schema;
            }
        );
    }

    private static ValueRule<Double> numberRule(double min, double max) {
        return numberRule(min, max, false);
    }

    private static ValueRule<Double> numberExclusiveMinRule(double min, double max) {
        return numberRule(min, max, true);
    }

    private static ValueRule<Double> numberRule(
        double min,
        double max,
        boolean exclusiveMin
    ) {
        return rule(
            (field, element) -> {
                if (
                    !element.isJsonPrimitive()
                        || !element.getAsJsonPrimitive().isNumber()
                ) {
                    throw invalid(field + " must be numeric.");
                }
                double value = element.getAsDouble();
                if (!Double.isFinite(value)) {
                    throw invalid(field + " must be finite.");
                }
                boolean below = exclusiveMin ? value <= min : value < min;
                if (below || value > max) {
                    throw invalid(field + " out of range.");
                }
                return value;
            },
            () -> {
                JsonObject schema = typeSchema("number");
                schema.addProperty(
                    exclusiveMin ? "exclusiveMinimum" : "minimum",
                    min
                );
                schema.addProperty("maximum", max);
                return schema;
            }
        );
    }

    private static <E extends Enum<E>> ValueRule<E> enumRule(Class<E> type) {
        E[] values = type.getEnumConstants();
        return rule(
            (field, element) -> {
                if (
                    !element.isJsonPrimitive()
                        || !element.getAsJsonPrimitive().isString()
                ) {
                    throw invalid(field + " must be a string.");
                }
                try {
                    return Enum.valueOf(type, element.getAsString());
                } catch (IllegalArgumentException failure) {
                    throw invalid(field + " has an unsupported value.");
                }
            },
            () -> {
                JsonObject schema = typeSchema("string");
                JsonArray choices = new JsonArray();
                for (E value : values) {
                    choices.add(value.name());
                }
                schema.add("enum", choices);
                return schema;
            }
        );
    }

    private static ValueRule<String> constStringRule(String expected) {
        return rule(
            (field, element) -> {
                if (
                    !element.isJsonPrimitive()
                        || !element.getAsJsonPrimitive().isString()
                        || !expected.equals(element.getAsString())
                ) {
                    throw invalid(field + " must be " + expected + ".");
                }
                return expected;
            },
            () -> {
                JsonObject schema = typeSchema("string");
                schema.addProperty("const", expected);
                return schema;
            }
        );
    }

    private static <T> ValueRule<T> objectRule(
        ObjectShape<? extends T> shape
    ) {
        return rule(
            (field, element) -> {
                if (!element.isJsonObject()) {
                    throw invalid(field + " must be an object.");
                }
                return shape.parse(element.getAsJsonObject());
            },
            shape::schema
        );
    }

    private static <T> ValueRule<T> rule(
        BiFunction<String, JsonElement, T> parser,
        Supplier<JsonObject> schema
    ) {
        return new ValueRule<>() {
            @Override
            public T parse(String field, JsonElement element) {
                return parser.apply(field, element);
            }

            @Override
            public JsonObject schema() {
                return schema.get();
            }
        };
    }

    private static JsonObject typeSchema(String type) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        return schema;
    }

    private static JsonObject nullableTypeSchema(String primaryType) {
        JsonObject schema = new JsonObject();
        JsonArray types = new JsonArray();
        types.add(primaryType);
        types.add("null");
        schema.add("type", types);
        return schema;
    }

    private static long primitiveLong(JsonElement element, String field) {
        if (
            !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()
        ) {
            throw invalid(field + " must be integer.");
        }
        try {
            String raw = element.getAsJsonPrimitive().getAsString();
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                throw invalid(field + " must be integer.");
            }
            return Long.parseLong(raw);
        } catch (NumberFormatException failure) {
            throw invalid(field + " must be integer.");
        }
    }

    private static ProtocolException invalid(String message) {
        return new ProtocolException(ErrorCode.INVALID_ARGUMENT, message);
    }

    public record AiDefinition(
        String name,
        ToolName coreTool,
        String description,
        JsonObject parameters
    ) {
        public AiDefinition {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(coreTool, "coreTool");
            Objects.requireNonNull(description, "description");
            parameters = Objects.requireNonNull(
                parameters,
                "parameters"
            ).deepCopy();
        }

        @Override
        public JsonObject parameters() {
            return parameters.deepCopy();
        }
    }

    private record Variant(
        String aiName,
        ToolName coreTool,
        String description,
        ObjectShape<? extends ToolArguments> shape,
        ToolName scheduledTool,
        String selectorField
    ) {
        private Variant {
            Objects.requireNonNull(aiName, "aiName");
            Objects.requireNonNull(coreTool, "coreTool");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(shape, "shape");
        }

        private ToolArguments parse(JsonObject arguments) {
            return shape.parse(arguments);
        }

        private AiDefinition definition() {
            return new AiDefinition(
                aiName,
                coreTool,
                description,
                shape.schema()
            );
        }
    }

    private record Field<T>(String name, ValueRule<T> rule) {
    }

    private interface ValueRule<T> {
        T parse(String field, JsonElement element);

        JsonObject schema();
    }

    private static final class ObjectShape<T> {
        private final List<Field<?>> fields;
        private final Function<Values, T> factory;

        private ObjectShape(
            List<Field<?>> fields,
            Function<Values, T> factory
        ) {
            this.fields = List.copyOf(fields);
            this.factory = Objects.requireNonNull(factory, "factory");

            Set<String> names = new HashSet<>();
            for (Field<?> field : this.fields) {
                if (!names.add(field.name())) {
                    throw new IllegalArgumentException(
                        "Duplicate ToolSpec field: " + field.name()
                    );
                }
            }
        }

        private T parse(JsonObject object) {
            Set<String> expected = new HashSet<>();
            fields.forEach(field -> expected.add(field.name()));
            if (!object.keySet().equals(expected)) {
                Set<String> unknown = new HashSet<>(object.keySet());
                unknown.removeAll(expected);
                Set<String> missing = new HashSet<>(expected);
                missing.removeAll(object.keySet());
                throw invalid(
                    "Field mismatch. unknown=" + unknown + ", missing=" + missing
                );
            }

            Map<String, Object> parsed = new LinkedHashMap<>();
            for (Field<?> field : fields) {
                parsed.put(
                    field.name(),
                    field.rule().parse(field.name(), object.get(field.name()))
                );
            }
            return factory.apply(new Values(parsed));
        }

        private JsonObject schema() {
            JsonObject schema = typeSchema("object");
            schema.addProperty("additionalProperties", false);

            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();
            for (Field<?> field : fields) {
                properties.add(field.name(), field.rule().schema());
                required.add(field.name());
            }
            schema.add("properties", properties);
            schema.add("required", required);
            return schema;
        }
    }

    private static final class Values {
        private final Map<String, Object> values;

        private Values(Map<String, Object> values) {
            this.values = Collections.unmodifiableMap(
                new LinkedHashMap<>(values)
            );
        }

        @SuppressWarnings("unchecked")
        private <T> T get(String name) {
            return (T) values.get(name);
        }
    }
}
