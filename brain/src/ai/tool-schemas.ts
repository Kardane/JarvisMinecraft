import type { FunctionTool } from "openai/resources/responses/responses";

import type {
  JsonObject,
  JsonValue,
  ModelToolCall,
  ToolDescriptor,
  ToolName,
} from "../core/types.js";

const UUID_PATTERN =
  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

const locationSchema = {
  type: "object",
  additionalProperties: false,
  required: ["worldId", "x", "y", "z", "yaw", "pitch"],
  properties: {
    worldId: { type: "string", minLength: 1, maxLength: 128 },
    x: { type: "number", minimum: -30_000_000, maximum: 30_000_000 },
    y: { type: "number", minimum: -2_048, maximum: 4_096 },
    z: { type: "number", minimum: -30_000_000, maximum: 30_000_000 },
    yaw: { type: "number", minimum: -360, maximum: 360 },
    pitch: { type: "number", minimum: -90, maximum: 90 },
  },
} as const;

interface AiToolDefinition {
  readonly name: string;
  readonly coreTool: ToolName;
  readonly description: string;
  readonly parameters: { readonly [key: string]: unknown };
}

export function buildFunctionTools(
  active: readonly ToolDescriptor[],
): FunctionTool[] {
  const output: FunctionTool[] = [];
  for (const descriptor of active) {
    for (const definition of definitionsFor(descriptor.name)) {
      output.push({
        type: "function",
        name: definition.name,
        description: definition.description,
        parameters: definition.parameters,
        strict: true,
      });
    }
  }
  return output;
}

export function translateFunctionCall(
  name: string,
  argumentsJson: string,
  activeCoreTools: ReadonlySet<ToolName>,
): ModelToolCall {
  const definition = definitionByAiName(name);
  if (!activeCoreTools.has(definition.coreTool)) {
    throw new Error("Luna requested an inactive Tool.");
  }

  const parsed = parseJsonObject(argumentsJson);
  const argumentsObject = validateArguments(definition.name, parsed);
  return {
    tool: definition.coreTool,
    arguments: argumentsObject,
  };
}

export function coreToolForAiName(name: string): ToolName {
  return definitionByAiName(name).coreTool;
}

function definitionsFor(tool: ToolName): readonly AiToolDefinition[] {
  switch (tool) {
    case "get_server_status":
      return [
        define(tool, tool, "Read current server performance/status metrics.", {
          type: "object",
          additionalProperties: false,
          properties: {},
          required: [],
        }),
      ];
    case "get_online_players":
      return [
        define(tool, tool, "List currently online players using bounded pagination.", {
          type: "object",
          additionalProperties: false,
          required: ["cursor", "limit"],
          properties: {
            cursor: { type: ["string", "null"], maxLength: 256 },
            limit: { type: "integer", minimum: 1, maximum: 100 },
          },
        }),
      ];
    case "get_player":
      return [
        define(
          "get_player_by_uuid",
          tool,
          "Resolve exactly one current player by UUID.",
          {
            type: "object",
            additionalProperties: false,
            required: ["playerUuid"],
            properties: {
              playerUuid: { type: "string", pattern: UUID_PATTERN },
            },
          },
        ),
        define(
          "get_player_by_name",
          tool,
          "Resolve exactly one current player by exact Minecraft name. Do not use fuzzy names.",
          {
            type: "object",
            additionalProperties: false,
            required: ["exactName"],
            properties: {
              exactName: { type: "string", minLength: 1, maxLength: 16 },
            },
          },
        ),
      ];
    case "get_player_location":
      return [uuidTool(tool, "Read the current location of one online player.")];
    case "get_nearby_players":
      return [
        define(tool, tool, "List online players near a known server location.", {
          type: "object",
          additionalProperties: false,
          required: ["center", "radius", "limit"],
          properties: {
            center: locationSchema,
            radius: { type: "number", exclusiveMinimum: 0, maximum: 64 },
            limit: { type: "integer", minimum: 1, maximum: 100 },
          },
        }),
      ];
    case "get_world_info":
      return [
        define(tool, tool, "Read supported information for a loaded world.", {
          type: "object",
          additionalProperties: false,
          required: ["worldId"],
          properties: {
            worldId: { type: "string", minLength: 1, maxLength: 128 },
          },
        }),
      ];
    case "teleport_staff":
      return [
        define(
          tool,
          tool,
          "Teleport only the requesting operator to an online target player. Use only after an explicit move request.",
          {
            type: "object",
            additionalProperties: false,
            required: ["targetPlayerUuid"],
            properties: {
              targetPlayerUuid: { type: "string", pattern: UUID_PATTERN },
            },
          },
        ),
      ];
    case "lookup_area_history":
      return [
        define(tool, tool, "Read bounded CoreProtect history around a known location.", {
          type: "object",
          additionalProperties: false,
          required: ["center", "radius", "lookbackSeconds", "cursor", "limit"],
          properties: {
            center: locationSchema,
            radius: { type: "integer", minimum: 0, maximum: 64 },
            lookbackSeconds: { type: "integer", minimum: 1, maximum: 86_400 },
            cursor: { type: ["string", "null"], maxLength: 256 },
            limit: { type: "integer", minimum: 1, maximum: 100 },
          },
        }),
      ];
    case "lookup_player_history":
      return [
        define(tool, tool, "Read bounded CoreProtect history for one player.", {
          type: "object",
          additionalProperties: false,
          required: ["playerUuid", "lookbackSeconds", "cursor", "limit"],
          properties: {
            playerUuid: { type: "string", pattern: UUID_PATTERN },
            lookbackSeconds: { type: "integer", minimum: 1, maximum: 86_400 },
            cursor: { type: ["string", "null"], maxLength: 256 },
            limit: { type: "integer", minimum: 1, maximum: 100 },
          },
        }),
      ];
    case "get_regions_at_location":
      return [
        define(tool, tool, "Read WorldGuard regions containing a known location.", {
          type: "object",
          additionalProperties: false,
          required: ["location"],
          properties: { location: locationSchema },
        }),
      ];
    case "get_region_info":
      return [
        define(tool, tool, "Read one exact WorldGuard region by world and region id.", {
          type: "object",
          additionalProperties: false,
          required: ["worldId", "regionId"],
          properties: {
            worldId: { type: "string", minLength: 1, maxLength: 128 },
            regionId: { type: "string", minLength: 1, maxLength: 128 },
          },
        }),
      ];
    case "check_build_permission":
      return [
        define(tool, tool, "Ask WorldGuard whether one player may build at a location.", {
          type: "object",
          additionalProperties: false,
          required: ["playerUuid", "location"],
          properties: {
            playerUuid: { type: "string", pattern: UUID_PATTERN },
            location: locationSchema,
          },
        }),
      ];
  }
}

function uuidTool(tool: ToolName, description: string): AiToolDefinition {
  return define(tool, tool, description, {
    type: "object",
    additionalProperties: false,
    required: ["playerUuid"],
    properties: {
      playerUuid: { type: "string", pattern: UUID_PATTERN },
    },
  });
}

function define(
  name: string,
  coreTool: ToolName,
  description: string,
  parameters: { readonly [key: string]: unknown },
): AiToolDefinition {
  return { name, coreTool, description, parameters };
}

function definitionByAiName(name: string): AiToolDefinition {
  const candidates: ToolName[] = [
    "get_server_status",
    "get_online_players",
    "get_player",
    "get_player_location",
    "get_nearby_players",
    "get_world_info",
    "teleport_staff",
    "lookup_area_history",
    "lookup_player_history",
    "get_regions_at_location",
    "get_region_info",
    "check_build_permission",
  ];
  for (const tool of candidates) {
    for (const definition of definitionsFor(tool)) {
      if (definition.name === name) {
        return definition;
      }
    }
  }
  throw new Error("Luna requested an unknown function Tool.");
}

function parseJsonObject(value: string): JsonObject {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    throw new Error("Luna returned invalid Tool JSON.");
  }
  if (!isPlainObject(parsed)) {
    throw new Error("Luna Tool arguments must be an object.");
  }
  return parsed as JsonObject;
}

function validateArguments(name: string, value: JsonObject): JsonObject {
  switch (name) {
    case "get_server_status":
      exactKeys(value, []);
      return value;
    case "get_online_players":
      exactKeys(value, ["cursor", "limit"]);
      nullableString(value.cursor, "cursor", 256);
      integer(value.limit, "limit", 1, 100);
      return value;
    case "get_player_by_uuid":
      exactKeys(value, ["playerUuid"]);
      uuid(value.playerUuid, "playerUuid");
      return value;
    case "get_player_by_name":
      exactKeys(value, ["exactName"]);
      boundedString(value.exactName, "exactName", 1, 16);
      return value;
    case "get_player_location":
      exactKeys(value, ["playerUuid"]);
      uuid(value.playerUuid, "playerUuid");
      return value;
    case "get_nearby_players":
      exactKeys(value, ["center", "radius", "limit"]);
      location(value.center, "center");
      numberRange(value.radius, "radius", Number.MIN_VALUE, 64, true);
      integer(value.limit, "limit", 1, 100);
      return value;
    case "get_world_info":
      exactKeys(value, ["worldId"]);
      boundedString(value.worldId, "worldId", 1, 128);
      return value;
    case "teleport_staff":
      exactKeys(value, ["targetPlayerUuid"]);
      uuid(value.targetPlayerUuid, "targetPlayerUuid");
      return value;
    case "lookup_area_history":
      exactKeys(value, ["center", "radius", "lookbackSeconds", "cursor", "limit"]);
      location(value.center, "center");
      integer(value.radius, "radius", 0, 64);
      integer(value.lookbackSeconds, "lookbackSeconds", 1, 86_400);
      nullableString(value.cursor, "cursor", 256);
      integer(value.limit, "limit", 1, 100);
      return value;
    case "lookup_player_history":
      exactKeys(value, ["playerUuid", "lookbackSeconds", "cursor", "limit"]);
      uuid(value.playerUuid, "playerUuid");
      integer(value.lookbackSeconds, "lookbackSeconds", 1, 86_400);
      nullableString(value.cursor, "cursor", 256);
      integer(value.limit, "limit", 1, 100);
      return value;
    case "get_regions_at_location":
      exactKeys(value, ["location"]);
      location(value.location, "location");
      return value;
    case "get_region_info":
      exactKeys(value, ["worldId", "regionId"]);
      boundedString(value.worldId, "worldId", 1, 128);
      boundedString(value.regionId, "regionId", 1, 128);
      return value;
    case "check_build_permission":
      exactKeys(value, ["playerUuid", "location"]);
      uuid(value.playerUuid, "playerUuid");
      location(value.location, "location");
      return value;
    default:
      throw new Error("No validator exists for Luna Tool.");
  }
}

function exactKeys(value: JsonObject, expected: readonly string[]): void {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new Error("Luna Tool arguments contain missing or unknown fields.");
  }
}

function location(value: JsonValue | undefined, field: string): void {
  if (!isPlainObject(value)) {
    throw new Error(field + " must be a location object.");
  }
  const object = value as JsonObject;
  exactKeys(object, ["worldId", "x", "y", "z", "yaw", "pitch"]);
  boundedString(object.worldId, field + ".worldId", 1, 128);
  numberRange(object.x, field + ".x", -30_000_000, 30_000_000);
  numberRange(object.y, field + ".y", -2_048, 4_096);
  numberRange(object.z, field + ".z", -30_000_000, 30_000_000);
  numberRange(object.yaw, field + ".yaw", -360, 360);
  numberRange(object.pitch, field + ".pitch", -90, 90);
}

function uuid(value: JsonValue | undefined, field: string): void {
  const text = boundedString(value, field, 36, 36);
  if (!new RegExp(UUID_PATTERN).test(text)) {
    throw new Error(field + " must be a UUID.");
  }
}

function boundedString(
  value: JsonValue | undefined,
  field: string,
  min: number,
  max: number,
): string {
  if (typeof value !== "string" || value.length < min || value.length > max) {
    throw new Error(field + " is outside string bounds.");
  }
  return value;
}

function nullableString(
  value: JsonValue | undefined,
  field: string,
  max: number,
): void {
  if (value === null) {
    return;
  }
  boundedString(value, field, 0, max);
}

function integer(
  value: JsonValue | undefined,
  field: string,
  min: number,
  max: number,
): void {
  if (typeof value !== "number" || !Number.isInteger(value) || value < min || value > max) {
    throw new Error(field + " must be an integer in range.");
  }
}

function numberRange(
  value: JsonValue | undefined,
  field: string,
  min: number,
  max: number,
  exclusiveMin = false,
): void {
  if (
    typeof value !== "number" ||
    !Number.isFinite(value) ||
    (exclusiveMin ? value <= min : value < min) ||
    value > max
  ) {
    throw new Error(field + " must be a number in range.");
  }
}

function isPlainObject(value: unknown): value is Record<string, JsonValue> {
  return (
    value !== null &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    Object.getPrototypeOf(value) === Object.prototype
  );
}
