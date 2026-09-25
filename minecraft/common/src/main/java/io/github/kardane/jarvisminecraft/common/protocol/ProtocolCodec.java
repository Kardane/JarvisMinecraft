package io.github.kardane.jarvisminecraft.common.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.BuildDecision;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.CancelReason;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ErrorCode;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.MessageType;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ResultStatus;
import static io.github.kardane.jarvisminecraft.common.protocol.Protocol.ToolName;
import static io.github.kardane.jarvisminecraft.common.protocol.ToolModels.*;

public final class ProtocolCodec {
    private static final Pattern SERVER_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private static final Set<String> COMMON_FIELDS = Set.of(
        "protocolVersion", "type", "messageId", "requestId", "serverId",
        "sessionId", "requesterUuid", "sentAt", "deadlineAt", "payload"
    );
    private static final Set<String> TOOL_FIELDS = Set.of(
        "protocolVersion", "type", "messageId", "requestId", "serverId",
        "sessionId", "requesterUuid", "sentAt", "deadlineAt", "payload",
        "toolCallId", "actionId"
    );

    private final Gson gson = new GsonBuilder()
        .serializeNulls()
        .registerTypeAdapter(Instant.class, new InstantJsonAdapter())
        .create();

    public ProtocolMessage decode(String json) {
        if (json == null) {
            throw invalid("Message is null.");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_MESSAGE_BYTES) {
            throw invalid("Message exceeds 65536 bytes.");
        }

        final JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw invalid("Message root must be an object.");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new ProtocolException(ErrorCode.INVALID_ARGUMENT, "Invalid JSON.", e);
        }

        String version = string(root, "protocolVersion", 1, 16);
        if (!Protocol.VERSION.equals(version)) {
            throw new ProtocolException(ErrorCode.UNSUPPORTED, "Unsupported protocol version.");
        }

        MessageType type = MessageType.fromWire(string(root, "type", 1, 64));
        exactFields(root, type == MessageType.TOOL_REQUEST || type == MessageType.TOOL_RESULT
            ? TOOL_FIELDS
            : COMMON_FIELDS);

        UUID messageId = uuid(root, "messageId", false);
        UUID requestId = uuid(root, "requestId", true);
        String serverId = string(root, "serverId", 1, 64);
        if (!SERVER_ID.matcher(serverId).matches()) {
            throw invalid("Invalid serverId.");
        }
        UUID sessionId = uuid(root, "sessionId", true);
        UUID requesterUuid = uuid(root, "requesterUuid", true);
        Instant sentAt = instant(root, "sentAt", false);
        Instant deadlineAt = instant(root, "deadlineAt", true);
        JsonObject payload = object(root, "payload");

        UUID toolCallId = null;
        UUID actionId = null;
        ProtocolMessage.Payload typedPayload;

        switch (type) {
            case HELLO -> {
                requireNull(requestId, "requestId");
                requireNull(sessionId, "sessionId");
                requireNull(requesterUuid, "requesterUuid");
                requireNull(deadlineAt, "deadlineAt");
                typedPayload = parseHello(payload);
            }
            case CAPABILITIES -> {
                requireNull(requestId, "requestId");
                requireNull(sessionId, "sessionId");
                requireNull(requesterUuid, "requesterUuid");
                requireNull(deadlineAt, "deadlineAt");
                typedPayload = parseCapabilities(payload);
            }
            case CHAT_MESSAGE -> {
                requireBusinessIds(requestId, sessionId, requesterUuid, deadlineAt);
                typedPayload = parseChatMessage(payload);
            }
            case CHAT_RESPONSE -> {
                requireBusinessIds(requestId, sessionId, requesterUuid, deadlineAt);
                typedPayload = parseChatResponse(payload);
            }
            case TOOL_REQUEST -> {
                requireBusinessIds(requestId, sessionId, requesterUuid, deadlineAt);
                toolCallId = uuid(root, "toolCallId", false);
                actionId = uuid(root, "actionId", true);
                ProtocolMessage.ToolRequest request = parseToolRequest(payload);
                validateActionId(request.tool(), actionId);
                typedPayload = request;
            }
            case TOOL_RESULT -> {
                requireBusinessIds(requestId, sessionId, requesterUuid, deadlineAt);
                toolCallId = uuid(root, "toolCallId", false);
                actionId = uuid(root, "actionId", true);
                ProtocolMessage.ToolResultPayload result = parseToolResult(payload);
                validateActionId(result.tool(), actionId);
                typedPayload = result;
            }
            case CANCEL -> typedPayload = parseCancel(payload);
            case ERROR -> typedPayload = parseError(payload);
            case PING -> {
                requireNull(requestId, "requestId");
                requireNull(sessionId, "sessionId");
                requireNull(requesterUuid, "requesterUuid");
                requireNull(deadlineAt, "deadlineAt");
                typedPayload = new ProtocolMessage.Ping(parseNonce(payload));
            }
            case PONG -> {
                requireNull(requestId, "requestId");
                requireNull(sessionId, "sessionId");
                requireNull(requesterUuid, "requesterUuid");
                requireNull(deadlineAt, "deadlineAt");
                typedPayload = new ProtocolMessage.Pong(parseNonce(payload));
            }
            default -> throw invalid("Unsupported message type.");
        }

        return new ProtocolMessage(
            version,
            type,
            messageId,
            requestId,
            serverId,
            sessionId,
            requesterUuid,
            sentAt,
            deadlineAt,
            typedPayload,
            toolCallId,
            actionId
        );
    }

    public String encode(ProtocolMessage message) {
        JsonObject root = new JsonObject();
        root.addProperty("protocolVersion", message.protocolVersion());
        root.addProperty("type", message.type().wireName());
        root.addProperty("messageId", message.messageId().toString());
        addUuid(root, "requestId", message.requestId());
        root.addProperty("serverId", message.serverId());
        addUuid(root, "sessionId", message.sessionId());
        addUuid(root, "requesterUuid", message.requesterUuid());
        root.addProperty("sentAt", message.sentAt().toString());
        if (message.deadlineAt() == null) {
            root.add("deadlineAt", JsonNull.INSTANCE);
        } else {
            root.addProperty("deadlineAt", message.deadlineAt().toString());
        }
        root.add("payload", payloadToJson(message.payload()));

        if (message.type() == MessageType.TOOL_REQUEST || message.type() == MessageType.TOOL_RESULT) {
            addUuid(root, "toolCallId", message.toolCallId());
            addUuid(root, "actionId", message.actionId());
        }
        return gson.toJson(root);
    }

    private ProtocolMessage.Payload parseHello(JsonObject payload) {
        String side = string(payload, "side", 1, 16);
        if ("adapter".equals(side)) {
            exactFields(payload, Set.of(
                "side", "adapterInstanceId", "platform", "minecraftVersion",
                "adapterVersion", "platformVersion"
            ));
            String platform = string(payload, "platform", 1, 32);
            if (!Set.of("paper", "fabric", "neoforge").contains(platform)) {
                throw invalid("Invalid adapter platform.");
            }
            String minecraftVersion = string(payload, "minecraftVersion", 1, 32);
            if (!"1.21.8".equals(minecraftVersion)) {
                throw new ProtocolException(ErrorCode.UNSUPPORTED, "Unsupported Minecraft version.");
            }
            return new ProtocolMessage.AdapterHello(
                side,
                uuid(payload, "adapterInstanceId", false),
                platform,
                minecraftVersion,
                string(payload, "adapterVersion", 1, 64),
                string(payload, "platformVersion", 1, 64)
            );
        }
        if ("brain".equals(side)) {
            exactFields(payload, Set.of("side", "brainInstanceId", "brainVersion", "accepted"));
            if (!bool(payload, "accepted")) {
                throw invalid("Brain hello must be accepted.");
            }
            return new ProtocolMessage.BrainHello(
                side,
                uuid(payload, "brainInstanceId", false),
                string(payload, "brainVersion", 1, 64),
                true
            );
        }
        throw invalid("Unknown hello side.");
    }

    private ProtocolMessage.Capabilities parseCapabilities(JsonObject payload) {
        exactFields(payload, Set.of("capabilities", "tools", "limits"));
        JsonArray capArray = array(payload, "capabilities", 64);
        List<ProtocolMessage.Capability> capabilities = new ArrayList<>();
        Set<String> capabilityNames = new HashSet<>();
        for (JsonElement element : capArray) {
            JsonObject cap = asObject(element, "capability");
            exactFields(cap, Set.of("name", "source", "version"));
            String name = string(cap, "name", 1, 128);
            if (!capabilityNames.add(name)) {
                throw invalid("Duplicate capability.");
            }
            capabilities.add(new ProtocolMessage.Capability(
                name,
                string(cap, "source", 1, 64),
                nullableString(cap, "version", 64)
            ));
        }

        JsonArray toolArray = array(payload, "tools", 64);
        List<ToolName> tools = new ArrayList<>();
        Set<ToolName> unique = new HashSet<>();
        for (JsonElement element : toolArray) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw invalid("Tool name must be a string.");
            }
            ToolName tool = ToolName.fromWire(element.getAsString());
            if (!unique.add(tool)) {
                throw invalid("Duplicate tool.");
            }
            tools.add(tool);
        }

        JsonObject limits = object(payload, "limits");
        exactFields(limits, Set.of(
            "maxMessageBytes", "maxToolCallsPerRequest", "maxModelRoundTripsPerRequest"
        ));
        int maxMessageBytes = integer(limits, "maxMessageBytes", 1, Integer.MAX_VALUE);
        int maxToolCalls = integer(limits, "maxToolCallsPerRequest", 1, 100);
        int maxRounds = integer(limits, "maxModelRoundTripsPerRequest", 1, 100);
        if (maxMessageBytes != Protocol.MAX_MESSAGE_BYTES || maxToolCalls != 8 || maxRounds != 4) {
            throw invalid("Capability limits do not match protocol 1.0.");
        }

        return new ProtocolMessage.Capabilities(
            List.copyOf(capabilities),
            List.copyOf(tools),
            new ProtocolMessage.Limits(maxMessageBytes, maxToolCalls, maxRounds)
        );
    }

    private ProtocolMessage.ChatMessage parseChatMessage(JsonObject payload) {
        exactFields(payload, Set.of("requesterName", "text", "mode"));
        String mode = string(payload, "mode", 1, 16);
        if (!Set.of("DIRECT", "FOLLOW_UP").contains(mode)) {
            throw invalid("Invalid chat mode.");
        }
        return new ProtocolMessage.ChatMessage(
            string(payload, "requesterName", 1, 16),
            string(payload, "text", 1, 4096),
            mode
        );
    }

    private ProtocolMessage.ChatResponse parseChatResponse(JsonObject payload) {
        exactFields(payload, Set.of("text", "final", "sessionState"));
        String state = string(payload, "sessionState", 1, 16);
        if (!Set.of("CONTINUE", "END").contains(state)) {
            throw invalid("Invalid session state.");
        }
        return new ProtocolMessage.ChatResponse(
            string(payload, "text", 1, 12000),
            bool(payload, "final"),
            state
        );
    }

    private ProtocolMessage.ToolRequest parseToolRequest(JsonObject payload) {
        exactFields(payload, Set.of("tool", "arguments"));
        ToolName tool = ToolName.fromWire(string(payload, "tool", 1, 64));
        JsonObject args = object(payload, "arguments");
        return new ProtocolMessage.ToolRequest(tool, parseArguments(tool, args));
    }

    private ToolArguments parseArguments(ToolName tool, JsonObject args) {
        return switch (tool) {
            case GET_SERVER_STATUS -> {
                exactFields(args, Set.of());
                yield new NoArguments();
            }
            case GET_ONLINE_PLAYERS -> {
                exactFields(args, Set.of("cursor", "limit"));
                yield new PagingArguments(
                    nullableString(args, "cursor", 256),
                    integer(args, "limit", 1, 100)
                );
            }
            case GET_PLAYER -> parseGetPlayerArguments(args);
            case GET_PLAYER_LOCATION -> {
                exactFields(args, Set.of("playerUuid"));
                yield new PlayerUuidArguments(uuid(args, "playerUuid", false));
            }
            case GET_CMI_PLAYER_INFO -> {
                exactFields(args, Set.of("playerUuid"));
                yield new PlayerUuidArguments(uuid(args, "playerUuid", false));
            }
            case GET_NEARBY_PLAYERS -> {
                exactFields(args, Set.of("center", "radius", "limit"));
                double radius = number(args, "radius");
                if (!(radius > 0.0 && radius <= 64.0)) {
                    throw invalid("Nearby radius must be > 0 and <= 64.");
                }
                yield new NearbyArguments(
                    parseLocation(object(args, "center")),
                    radius,
                    integer(args, "limit", 1, 100)
                );
            }
            case GET_WORLD_INFO -> {
                exactFields(args, Set.of("worldId"));
                yield new WorldInfoArguments(string(args, "worldId", 1, 128));
            }
            case TELEPORT_STAFF -> {
                exactFields(args, Set.of("targetPlayerUuid"));
                yield new TeleportArguments(uuid(args, "targetPlayerUuid", false));
            }
            case LOOKUP_AREA_HISTORY -> {
                exactFields(args, Set.of("center", "radius", "lookbackSeconds", "cursor", "limit"));
                yield new AreaHistoryArguments(
                    parseLocation(object(args, "center")),
                    integer(args, "radius", 0, 64),
                    integer(args, "lookbackSeconds", 1, 86_400),
                    nullableString(args, "cursor", 256),
                    integer(args, "limit", 1, 100)
                );
            }
            case LOOKUP_PLAYER_HISTORY -> {
                exactFields(args, Set.of("playerUuid", "lookbackSeconds", "cursor", "limit"));
                yield new PlayerHistoryArguments(
                    uuid(args, "playerUuid", false),
                    integer(args, "lookbackSeconds", 1, 86_400),
                    nullableString(args, "cursor", 256),
                    integer(args, "limit", 1, 100)
                );
            }
            case GET_REGIONS_AT_LOCATION -> {
                exactFields(args, Set.of("location"));
                yield new RegionsAtLocationArguments(parseLocation(object(args, "location")));
            }
            case GET_REGION_INFO -> {
                exactFields(args, Set.of("worldId", "regionId"));
                yield new RegionInfoArguments(
                    string(args, "worldId", 1, 128),
                    string(args, "regionId", 1, 128)
                );
            }
            case CHECK_BUILD_PERMISSION -> {
                exactFields(args, Set.of("playerUuid", "location"));
                yield new BuildPermissionArguments(
                    uuid(args, "playerUuid", false),
                    parseLocation(object(args, "location"))
                );
            }
        };
    }

    private ToolArguments parseGetPlayerArguments(JsonObject args) {
        if (args.size() != 1) {
            throw invalid("get_player requires exactly one selector.");
        }
        if (args.has("playerUuid")) {
            exactFields(args, Set.of("playerUuid"));
            return new GetPlayerByUuidArguments(uuid(args, "playerUuid", false));
        }
        if (args.has("exactName")) {
            exactFields(args, Set.of("exactName"));
            return new GetPlayerByNameArguments(string(args, "exactName", 1, 16));
        }
        throw invalid("get_player selector is invalid.");
    }

    private ProtocolMessage.ToolResultPayload parseToolResult(JsonObject payload) {
        exactFields(payload, Set.of("tool", "result"));
        ToolName tool = ToolName.fromWire(string(payload, "tool", 1, 64));
        JsonObject result = object(payload, "result");
        exactFields(result, Set.of("status", "data", "error", "observedAt", "source", "truncated"));

        ResultStatus status;
        try {
            status = ResultStatus.valueOf(string(result, "status", 1, 32));
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid tool result status.");
        }

        JsonElement dataElement = required(result, "data");
        JsonElement errorElement = required(result, "error");
        boolean hasData = !dataElement.isJsonNull();
        boolean hasError = !errorElement.isJsonNull();

        if ((status == ResultStatus.ERROR || status == ResultStatus.UNSUPPORTED) && (hasData || !hasError)) {
            throw invalid("Error result must contain error and data=null.");
        }
        if ((status == ResultStatus.OK || status == ResultStatus.EMPTY) && hasError) {
            throw invalid("Successful result must have error=null.");
        }

        ToolData data = hasData ? parseToolData(tool, asObject(dataElement, "result.data")) : null;
        ErrorObject error = hasError ? parseErrorObject(asObject(errorElement, "result.error")) : null;

        return new ProtocolMessage.ToolResultPayload(
            tool,
            new ToolResult(
                status,
                data,
                error,
                instant(result, "observedAt", false),
                string(result, "source", 1, 128),
                bool(result, "truncated")
            )
        );
    }

    private ToolData parseToolData(ToolName tool, JsonObject data) {
        return switch (tool) {
            case GET_SERVER_STATUS -> parseServerStatus(data);
            case GET_ONLINE_PLAYERS -> parseOnlinePlayers(data);
            case GET_PLAYER -> parsePlayerData(data);
            case GET_PLAYER_LOCATION -> parsePlayerLocationData(data);
            case GET_CMI_PLAYER_INFO -> parseCmiPlayerInfoData(data);
            case GET_NEARBY_PLAYERS -> parseNearbyPlayers(data);
            case GET_WORLD_INFO -> parseWorldInfo(data);
            case TELEPORT_STAFF -> parseTeleportData(data);
            case LOOKUP_AREA_HISTORY, LOOKUP_PLAYER_HISTORY -> parseHistoryData(data);
            case GET_REGIONS_AT_LOCATION -> parseRegionsAtLocation(data);
            case GET_REGION_INFO -> parseRegionInfo(data);
            case CHECK_BUILD_PERMISSION -> parseBuildPermission(data);
        };
    }

    private ServerStatusData parseServerStatus(JsonObject data) {
        exactFields(data, Set.of(
            "tps", "mspt", "onlinePlayers", "loadedChunks", "memoryUsedBytes", "memoryMaxBytes"
        ));
        return new ServerStatusData(
            parseMetric(object(data, "tps")),
            parseMetric(object(data, "mspt")),
            parseMetric(object(data, "onlinePlayers")),
            parseMetric(object(data, "loadedChunks")),
            parseMetric(object(data, "memoryUsedBytes")),
            parseMetric(object(data, "memoryMaxBytes"))
        );
    }

    private Metric parseMetric(JsonObject metric) {
        exactFields(metric, Set.of("value", "unit", "windowMs", "observedAt", "source"));
        JsonElement value = required(metric, "value");
        Double number = value.isJsonNull() ? null : primitiveNumber(value, "metric.value");
        JsonElement window = required(metric, "windowMs");
        Long windowMs = null;
        if (!window.isJsonNull()) {
            long parsed = primitiveLong(window, "metric.windowMs");
            if (parsed < 1) {
                throw invalid("Metric windowMs must be positive.");
            }
            windowMs = parsed;
        }
        return new Metric(
            number,
            string(metric, "unit", 1, 32),
            windowMs,
            instant(metric, "observedAt", false),
            string(metric, "source", 1, 128)
        );
    }

    private OnlinePlayersData parseOnlinePlayers(JsonObject data) {
        exactFields(data, Set.of("players", "returnedCount", "nextCursor"));
        List<PlayerRef> players = parsePlayerRefs(array(data, "players", 100));
        int count = integer(data, "returnedCount", 0, 100);
        if (count != players.size()) {
            throw invalid("returnedCount does not match players length.");
        }
        return new OnlinePlayersData(players, count, nullableString(data, "nextCursor", 256));
    }

    private PlayerData parsePlayerData(JsonObject data) {
        exactFields(data, Set.of("player", "online"));
        if (!bool(data, "online")) {
            throw invalid("player data online must be true.");
        }
        return new PlayerData(parsePlayerRef(object(data, "player")), true);
    }

    private PlayerLocationData parsePlayerLocationData(JsonObject data) {
        exactFields(data, Set.of("player", "location"));
        return new PlayerLocationData(
            parsePlayerRef(object(data, "player")),
            parseLocation(object(data, "location"))
        );
    }

    private CmiPlayerInfoData parseCmiPlayerInfoData(JsonObject data) {
        exactFields(data, Set.of("player", "nickname", "afk"));
        return new CmiPlayerInfoData(
            parsePlayerRef(object(data, "player")),
            string(data, "nickname", 1, 64),
            bool(data, "afk")
        );
    }

    private NearbyPlayersData parseNearbyPlayers(JsonObject data) {
        exactFields(data, Set.of("players", "returnedCount"));
        JsonArray array = array(data, "players", 100);
        List<NearbyPlayer> players = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject nearby = asObject(element, "nearby player");
            exactFields(nearby, Set.of("player", "distance"));
            double distance = number(nearby, "distance");
            if (distance < 0 || distance > 64) {
                throw invalid("Nearby distance out of range.");
            }
            players.add(new NearbyPlayer(parsePlayerRef(object(nearby, "player")), distance));
        }
        int count = integer(data, "returnedCount", 0, 100);
        if (count != players.size()) {
            throw invalid("returnedCount does not match nearby players length.");
        }
        return new NearbyPlayersData(List.copyOf(players), count);
    }

    private WorldInfoData parseWorldInfo(JsonObject data) {
        exactFields(data, Set.of("worldId", "dimensionKey", "playerCount", "difficulty", "timeOfDay"));
        Long time = nullableLong(data, "timeOfDay", 0);
        return new WorldInfoData(
            string(data, "worldId", 1, 128),
            nullableString(data, "dimensionKey", 128),
            integer(data, "playerCount", 0, Integer.MAX_VALUE),
            nullableString(data, "difficulty", 32),
            time
        );
    }

    private TeleportData parseTeleportData(JsonObject data) {
        exactFields(data, Set.of(
            "requesterUuid", "targetPlayerUuid", "fromWorldId", "toWorldId", "completed"
        ));
        if (!bool(data, "completed")) {
            throw invalid("Teleport result completed must be true.");
        }
        return new TeleportData(
            uuid(data, "requesterUuid", false),
            uuid(data, "targetPlayerUuid", false),
            string(data, "fromWorldId", 1, 128),
            string(data, "toWorldId", 1, 128),
            true
        );
    }

    private HistoryData parseHistoryData(JsonObject data) {
        exactFields(data, Set.of(
            "records", "returnedCount", "totalCount", "nextCursor", "windowStart", "windowEnd"
        ));
        JsonArray recordsJson = array(data, "records", 100);
        List<BlockChange> records = new ArrayList<>();
        for (JsonElement element : recordsJson) {
            JsonObject item = asObject(element, "history record");
            exactFields(item, Set.of("timestamp", "actorUuid", "actorName", "action", "location", "material"));
            String action = string(item, "action", 1, 16);
            if (!Set.of("BREAK", "PLACE").contains(action)) {
                throw invalid("Invalid history action.");
            }
            records.add(new BlockChange(
                instant(item, "timestamp", false),
                uuid(item, "actorUuid", true),
                string(item, "actorName", 1, 64),
                action,
                parseLocation(object(item, "location")),
                string(item, "material", 1, 128)
            ));
        }
        int returned = integer(data, "returnedCount", 0, 100);
        if (returned != records.size()) {
            throw invalid("History returnedCount mismatch.");
        }
        Integer total = nullableInteger(data, "totalCount", 0);
        return new HistoryData(
            List.copyOf(records),
            returned,
            total,
            nullableString(data, "nextCursor", 256),
            instant(data, "windowStart", false),
            instant(data, "windowEnd", false)
        );
    }

    private RegionsAtLocationData parseRegionsAtLocation(JsonObject data) {
        exactFields(data, Set.of("regions"));
        JsonArray regions = array(data, "regions", 100);
        List<RegionSummary> output = new ArrayList<>();
        for (JsonElement element : regions) {
            JsonObject region = asObject(element, "region");
            exactFields(region, Set.of("id", "priority", "owners", "members"));
            output.add(new RegionSummary(
                string(region, "id", 1, 128),
                integer(region, "priority", Integer.MIN_VALUE, Integer.MAX_VALUE),
                stringList(array(region, "owners", 100), 64),
                stringList(array(region, "members", 100), 64)
            ));
        }
        return new RegionsAtLocationData(List.copyOf(output));
    }

    private RegionInfoData parseRegionInfo(JsonObject data) {
        exactFields(data, Set.of(
            "id", "worldId", "priority", "parentId", "owners", "members", "flags"
        ));
        JsonObject flags = object(data, "flags");
        if (flags.size() > 128) {
            throw invalid("Too many region flags.");
        }
        for (Map.Entry<String, JsonElement> entry : flags.entrySet()) {
            JsonElement value = entry.getValue();
            if (!(value.isJsonNull() || value.isJsonPrimitive())) {
                throw invalid("Region flag must be scalar.");
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> flagMap = gson.fromJson(flags, Map.class);
        return new RegionInfoData(
            string(data, "id", 1, 128),
            string(data, "worldId", 1, 128),
            integer(data, "priority", Integer.MIN_VALUE, Integer.MAX_VALUE),
            nullableString(data, "parentId", 128),
            stringList(array(data, "owners", 100), 64),
            stringList(array(data, "members", 100), 64),
            Map.copyOf(flagMap)
        );
    }

    private BuildPermissionData parseBuildPermission(JsonObject data) {
        exactFields(data, Set.of("decision", "bypass", "matchedRegions", "reason"));
        BuildDecision decision;
        try {
            decision = BuildDecision.valueOf(string(data, "decision", 1, 32));
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid build decision.");
        }
        return new BuildPermissionData(
            decision,
            bool(data, "bypass"),
            stringList(array(data, "matchedRegions", 100), 128),
            nullableString(data, "reason", 512)
        );
    }

    private ProtocolMessage.Cancel parseCancel(JsonObject payload) {
        exactFields(payload, Set.of("targetRequestId", "reason"));
        CancelReason reason;
        try {
            reason = CancelReason.valueOf(string(payload, "reason", 1, 64));
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid cancel reason.");
        }
        return new ProtocolMessage.Cancel(uuid(payload, "targetRequestId", false), reason);
    }

    private ProtocolMessage.ErrorPayload parseError(JsonObject payload) {
        exactFields(payload, Set.of("scope", "error"));
        String scope = string(payload, "scope", 1, 32);
        if (!Set.of("CONNECTION", "REQUEST").contains(scope)) {
            throw invalid("Invalid error scope.");
        }
        return new ProtocolMessage.ErrorPayload(scope, parseErrorObject(object(payload, "error")));
    }

    private ErrorObject parseErrorObject(JsonObject error) {
        exactFields(error, Set.of("code", "message", "retryable", "details"));
        ErrorCode code;
        try {
            code = ErrorCode.valueOf(string(error, "code", 1, 64));
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid error code.");
        }
        JsonObject details = object(error, "details");
        if (details.size() > 10) {
            throw invalid("Too many error detail fields.");
        }
        for (JsonElement value : details.asMap().values()) {
            if (!(value.isJsonNull() || value.isJsonPrimitive())) {
                throw invalid("Error detail must be scalar.");
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> detailMap = gson.fromJson(details, Map.class);
        return new ErrorObject(
            code,
            string(error, "message", 1, 512),
            bool(error, "retryable"),
            detailMap
        );
    }

    private String parseNonce(JsonObject payload) {
        exactFields(payload, Set.of("nonce"));
        return string(payload, "nonce", 1, 128);
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

    private PlayerRef parsePlayerRef(JsonObject player) {
        exactFields(player, Set.of("uuid", "name"));
        return new PlayerRef(uuid(player, "uuid", false), string(player, "name", 1, 16));
    }

    private List<PlayerRef> parsePlayerRefs(JsonArray array) {
        List<PlayerRef> output = new ArrayList<>();
        for (JsonElement element : array) {
            output.add(parsePlayerRef(asObject(element, "player")));
        }
        return List.copyOf(output);
    }

    private List<String> stringList(JsonArray array, int maxLength) {
        List<String> output = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw invalid("Array value must be a string.");
            }
            String value = element.getAsString();
            if (value.length() > maxLength) {
                throw invalid("String value is too long.");
            }
            output.add(value);
        }
        return List.copyOf(output);
    }

    private JsonObject payloadToJson(ProtocolMessage.Payload payload) {
        JsonObject object = gson.toJsonTree(payload).getAsJsonObject();
        if (payload instanceof ProtocolMessage.ChatResponse response) {
            object.remove("finalResponse");
            object.addProperty("final", response.finalResponse());
        }
        if (payload instanceof ProtocolMessage.ToolRequest request) {
            object.addProperty("tool", request.tool().wireName());
            object.add("arguments", gson.toJsonTree(request.arguments()));
        } else if (payload instanceof ProtocolMessage.ToolResultPayload result) {
            object.addProperty("tool", result.tool().wireName());
            object.add("result", toolResultToJson(result.result()));
        } else if (payload instanceof ProtocolMessage.Capabilities capabilities) {
            JsonArray tools = new JsonArray();
            for (ToolName tool : capabilities.tools()) {
                tools.add(tool.wireName());
            }
            object.add("tools", tools);
        }
        return object;
    }

    private JsonObject toolResultToJson(ToolResult result) {
        JsonObject object = new JsonObject();
        object.addProperty("status", result.status().name());
        object.add("data", result.data() == null ? JsonNull.INSTANCE : gson.toJsonTree(result.data()));
        object.add("error", result.error() == null ? JsonNull.INSTANCE : gson.toJsonTree(result.error()));
        object.addProperty("observedAt", result.observedAt().toString());
        object.addProperty("source", result.source());
        object.addProperty("truncated", result.truncated());
        return object;
    }

    private void validateActionId(ToolName tool, UUID actionId) {
        if (tool.stateChanging() && actionId == null) {
            throw invalid("State-changing tool requires actionId.");
        }
        if (!tool.stateChanging() && actionId != null) {
            throw invalid("Read-only tool requires actionId=null.");
        }
    }

    private void requireBusinessIds(UUID requestId, UUID sessionId, UUID requesterUuid, Instant deadlineAt) {
        if (requestId == null || sessionId == null || requesterUuid == null || deadlineAt == null) {
            throw invalid("Business message requires request/session/requester/deadline.");
        }
    }

    private void requireNull(Object value, String field) {
        if (value != null) {
            throw invalid(field + " must be null.");
        }
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
        return asObject(required(parent, field), field);
    }

    private JsonObject asObject(JsonElement element, String field) {
        if (element == null || !element.isJsonObject()) {
            throw invalid(field + " must be an object.");
        }
        return element.getAsJsonObject();
    }

    private JsonArray array(JsonObject parent, String field, int maxItems) {
        JsonElement element = required(parent, field);
        if (!element.isJsonArray()) {
            throw invalid(field + " must be an array.");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() > maxItems) {
            throw invalid(field + " has too many items.");
        }
        return array;
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

    private boolean bool(JsonObject object, String field) {
        JsonElement element = required(object, field);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw invalid(field + " must be boolean.");
        }
        return element.getAsBoolean();
    }

    private UUID uuid(JsonObject object, String field, boolean nullable) {
        JsonElement element = required(object, field);
        if (element.isJsonNull()) {
            if (nullable) {
                return null;
            }
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

    private Instant instant(JsonObject object, String field, boolean nullable) {
        JsonElement element = required(object, field);
        if (element.isJsonNull()) {
            if (nullable) {
                return null;
            }
            throw invalid(field + " must not be null.");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw invalid(field + " must be date-time string.");
        }
        try {
            return Instant.parse(element.getAsString());
        } catch (DateTimeParseException e) {
            throw invalid(field + " is not RFC3339 UTC date-time.");
        }
    }

    private int integer(JsonObject object, String field, int min, int max) {
        JsonElement element = required(object, field);
        long value = primitiveLong(element, field);
        if (value < min || value > max) {
            throw invalid(field + " out of range.");
        }
        return (int) value;
    }

    private Integer nullableInteger(JsonObject object, String field, int min) {
        JsonElement element = required(object, field);
        if (element.isJsonNull()) {
            return null;
        }
        long value = primitiveLong(element, field);
        if (value < min || value > Integer.MAX_VALUE) {
            throw invalid(field + " out of range.");
        }
        return (int) value;
    }

    private Long nullableLong(JsonObject object, String field, long min) {
        JsonElement element = required(object, field);
        if (element.isJsonNull()) {
            return null;
        }
        long value = primitiveLong(element, field);
        if (value < min) {
            throw invalid(field + " out of range.");
        }
        return value;
    }

    private double number(JsonObject object, String field) {
        return primitiveNumber(required(object, field), field);
    }

    private double primitiveNumber(JsonElement element, String field) {
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

    private void addUuid(JsonObject object, String field, UUID value) {
        if (value == null) {
            object.add(field, JsonNull.INSTANCE);
        } else {
            object.addProperty(field, value.toString());
        }
    }

    private ProtocolException invalid(String message) {
        return new ProtocolException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private static final class InstantJsonAdapter
        implements JsonSerializer<Instant>, JsonDeserializer<Instant> {

        @Override
        public JsonElement serialize(Instant src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public Instant deserialize(
            JsonElement json,
            Type typeOfT,
            JsonDeserializationContext context
        ) throws JsonParseException {
            return Instant.parse(json.getAsString());
        }
    }
}
