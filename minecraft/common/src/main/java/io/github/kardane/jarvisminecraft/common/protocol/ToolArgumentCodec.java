package io.github.kardane.jarvisminecraft.common.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;
import static io.github.kardane.jarvisminecraft.common.protocol.ToolModels.*;

public final class ToolArgumentCodec {
    public ToolArguments parse(ToolName tool, JsonObject arguments) {
        if (tool == null) {
            throw invalid("Tool must not be null.");
        }
        if (arguments == null) {
            throw invalid("Tool arguments must be an object.");
        }

        return switch (tool) {
            case GET_SERVER_STATUS -> {
                exactFields(arguments, Set.of());
                yield new NoArguments();
            }
            case GET_ONLINE_PLAYERS -> {
                exactFields(arguments, Set.of("cursor", "limit"));
                yield new PagingArguments(
                    nullableString(arguments, "cursor", 256),
                    integer(arguments, "limit", 1, 100)
                );
            }
            case GET_PLAYER -> parseGetPlayerArguments(arguments);
            case GET_PLAYER_LOCATION, GET_CMI_PLAYER_INFO -> {
                exactFields(arguments, Set.of("playerUuid"));
                yield new PlayerUuidArguments(uuid(arguments, "playerUuid"));
            }
            case GET_NEARBY_PLAYERS -> {
                exactFields(arguments, Set.of("center", "radius", "limit"));
                double radius = number(arguments, "radius");
                if (!(radius > 0.0 && radius <= 64.0)) {
                    throw invalid("Nearby radius must be > 0 and <= 64.");
                }
                yield new NearbyArguments(
                    parseLocation(object(arguments, "center")),
                    radius,
                    integer(arguments, "limit", 1, 100)
                );
            }
            case GET_WORLD_INFO -> {
                exactFields(arguments, Set.of("worldId"));
                yield new WorldInfoArguments(string(arguments, "worldId", 1, 128));
            }
            case TELEPORT_STAFF -> {
                exactFields(arguments, Set.of("targetPlayerUuid"));
                yield new TeleportArguments(uuid(arguments, "targetPlayerUuid"));
            }
            case LOOKUP_AREA_HISTORY -> {
                exactFields(
                    arguments,
                    Set.of("center", "radius", "lookbackSeconds", "cursor", "limit")
                );
                yield new AreaHistoryArguments(
                    parseLocation(object(arguments, "center")),
                    integer(arguments, "radius", 0, 64),
                    integer(arguments, "lookbackSeconds", 1, 86_400),
                    nullableString(arguments, "cursor", 256),
                    integer(arguments, "limit", 1, 100)
                );
            }
            case LOOKUP_PLAYER_HISTORY -> {
                exactFields(
                    arguments,
                    Set.of("playerUuid", "lookbackSeconds", "cursor", "limit")
                );
                yield new PlayerHistoryArguments(
                    uuid(arguments, "playerUuid"),
                    integer(arguments, "lookbackSeconds", 1, 86_400),
                    nullableString(arguments, "cursor", 256),
                    integer(arguments, "limit", 1, 100)
                );
            }
            case GET_REGIONS_AT_LOCATION -> {
                exactFields(arguments, Set.of("location"));
                yield new RegionsAtLocationArguments(parseLocation(object(arguments, "location")));
            }
            case GET_REGION_INFO -> {
                exactFields(arguments, Set.of("worldId", "regionId"));
                yield new RegionInfoArguments(
                    string(arguments, "worldId", 1, 128),
                    string(arguments, "regionId", 1, 128)
                );
            }
            case CHECK_BUILD_PERMISSION -> {
                exactFields(arguments, Set.of("playerUuid", "location"));
                yield new BuildPermissionArguments(
                    uuid(arguments, "playerUuid"),
                    parseLocation(object(arguments, "location"))
                );
            }
        };
    }

    private ToolArguments parseGetPlayerArguments(JsonObject arguments) {
        if (arguments.size() != 1) {
            throw invalid("get_player requires exactly one selector.");
        }
        if (arguments.has("playerUuid")) {
            exactFields(arguments, Set.of("playerUuid"));
            return new GetPlayerByUuidArguments(uuid(arguments, "playerUuid"));
        }
        if (arguments.has("exactName")) {
            exactFields(arguments, Set.of("exactName"));
            return new GetPlayerByNameArguments(string(arguments, "exactName", 1, 16));
        }
        throw invalid("get_player selector is invalid.");
    }

    private Location parseLocation(JsonObject location) {
        exactFields(location, Set.of("worldId", "x", "y", "z", "yaw", "pitch"));
        double x = ranged(number(location, "x"), -30_000_000, 30_000_000, "x");
        double y = ranged(number(location, "y"), -2048, 4096, "y");
        double z = ranged(number(location, "z"), -30_000_000, 30_000_000, "z");
        double yaw = ranged(number(location, "yaw"), -360, 360, "yaw");
        double pitch = ranged(number(location, "pitch"), -90, 90, "pitch");
        return new Location(string(location, "worldId", 1, 128), x, y, z, yaw, pitch);
    }

    private void exactFields(JsonObject object, Set<String> expected) {
        if (!object.keySet().equals(expected)) {
            Set<String> unknown = new HashSet<>(object.keySet());
            unknown.removeAll(expected);
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(object.keySet());
            throw invalid("Field mismatch. unknown=" + unknown + ", missing=" + missing);
        }
    }

    private JsonElement required(JsonObject object, String field) {
        if (!object.has(field)) {
            throw invalid("Missing field: " + field);
        }
        return object.get(field);
    }

    private JsonObject object(JsonObject parent, String field) {
        JsonElement element = required(parent, field);
        if (!element.isJsonObject()) {
            throw invalid(field + " must be an object.");
        }
        return element.getAsJsonObject();
    }

    private String string(JsonObject object, String field, int min, int max) {
        JsonElement element = required(object, field);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw invalid(field + " must be a string.");
        }
        String value = element.getAsString();
        if (value.length() < min || value.length() > max) {
            throw invalid(field + " length is invalid.");
        }
        return value;
    }

    private String nullableString(JsonObject object, String field, int max) {
        JsonElement element = required(object, field);
        if (element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw invalid(field + " must be string or null.");
        }
        String value = element.getAsString();
        if (value.length() > max) {
            throw invalid(field + " is too long.");
        }
        return value;
    }

    private UUID uuid(JsonObject object, String field) {
        JsonElement element = required(object, field);
        if (element.isJsonNull()) {
            throw invalid(field + " must not be null.");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw invalid(field + " must be UUID string.");
        }
        try {
            return UUID.fromString(element.getAsString());
        } catch (IllegalArgumentException e) {
            throw invalid(field + " is not a UUID.");
        }
    }

    private int integer(JsonObject object, String field, int min, int max) {
        long value = primitiveLong(required(object, field), field);
        if (value < min || value > max) {
            throw invalid(field + " out of range.");
        }
        return (int) value;
    }

    private double number(JsonObject object, String field) {
        JsonElement element = required(object, field);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid(field + " must be numeric.");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw invalid(field + " must be finite.");
        }
        return value;
    }

    private long primitiveLong(JsonElement element, String field) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid(field + " must be integer.");
        }
        try {
            String raw = element.getAsJsonPrimitive().getAsString();
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                throw invalid(field + " must be integer.");
            }
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw invalid(field + " must be integer.");
        }
    }

    private double ranged(double value, double min, double max, String field) {
        if (value < min || value > max) {
            throw invalid(field + " out of range.");
        }
        return value;
    }

    private ProtocolException invalid(String message) {
        return new ProtocolException(ErrorCode.INVALID_ARGUMENT, message);
    }
}
