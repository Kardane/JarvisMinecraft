import { CoreError } from "./errors.js";
import type {
  CapabilitySnapshot,
  JsonObject,
  JsonValue,
  ToolDescriptor,
  ToolName,
} from "./types.js";

const CATALOG: Record<ToolName, ToolDescriptor> = {
  get_server_status: {
    name: "get_server_status",
    capability: "server.status",
    stateChanging: false,
    risk: "READ_ONLY",
  },
  get_online_players: {
    name: "get_online_players",
    capability: "player.list",
    stateChanging: false,
    risk: "READ_ONLY",
  },
  get_player: {
    name: "get_player",
    capability: "player.lookup",
    stateChanging: false,
    risk: "READ_ONLY",
  },
  get_player_location: {
    name: "get_player_location",
    capability: "player.location",
    stateChanging: false,
    risk: "READ_ONLY",
  },
  get_nearby_players: {
    name: "get_nearby_players",
    capability: "player.nearby",
    stateChanging: false,
    risk: "READ_ONLY",
  },
  get_world_info: {
    name: "get_world_info",
    capability: "world.info",
    stateChanging: false,
    risk: "READ_ONLY",
  },
  teleport_staff: {
    name: "teleport_staff",
    capability: "staff.self_teleport",
    stateChanging: true,
    risk: "LOW",
  },
  lookup_area_history: {
    name: "lookup_area_history",
    capability: "history.lookup",
    stateChanging: false,
    risk: "READ_ONLY",
  },
  lookup_player_history: {
    name: "lookup_player_history",
    capability: "history.lookup",
    stateChanging: false,
    risk: "READ_ONLY",
  },
  get_regions_at_location: {
    name: "get_regions_at_location",
    capability: "region.lookup",
    stateChanging: false,
    risk: "READ_ONLY",
  },
  get_region_info: {
    name: "get_region_info",
    capability: "region.lookup",
    stateChanging: false,
    risk: "READ_ONLY",
  },
  check_build_permission: {
    name: "check_build_permission",
    capability: "region.protection",
    stateChanging: false,
    risk: "READ_ONLY",
  },
  get_cmi_player_info: {
    name: "get_cmi_player_info",
    capability: "player.cmi_profile",
    stateChanging: false,
    risk: "READ_ONLY",
  },
};

export function getToolDescriptor(name: string): ToolDescriptor {
  if (!Object.prototype.hasOwnProperty.call(CATALOG, name)) {
    throw new CoreError("UNSUPPORTED", "Model requested an unregistered Tool.");
  }
  return CATALOG[name as ToolName];
}

export function resolveActiveTools(
  snapshot: CapabilitySnapshot,
): readonly ToolDescriptor[] {
  if (
    snapshot.limits.maxMessageBytes !== 65_536 ||
    snapshot.limits.maxToolCallsPerRequest !== 8 ||
    snapshot.limits.maxModelRoundTripsPerRequest !== 4
  ) {
    throw new CoreError("INVALID_ARGUMENT", "Adapter capability limits do not match protocol 1.0.");
  }

  const capabilityNames = new Set(snapshot.capabilities.map((item) => item.name));
  const seen = new Set<string>();
  const active: ToolDescriptor[] = [];

  for (const name of snapshot.tools) {
    if (seen.has(name)) {
      throw new CoreError("INVALID_ARGUMENT", "Adapter advertised a duplicate Tool.");
    }
    seen.add(name);

    const descriptor = getToolDescriptor(name);
    if (!capabilityNames.has(descriptor.capability)) {
      throw new CoreError(
        "INVALID_ARGUMENT",
        "Adapter advertised a Tool without its required capability.",
      );
    }
    active.push(descriptor);
  }

  return active;
}

export function summarizeArguments(argumentsObject: JsonObject): JsonObject {
  return sanitizeObject(argumentsObject, 0);
}

function sanitizeObject(value: JsonObject, depth: number): JsonObject {
  if (depth >= 4) {
    return {};
  }

  const output: Record<string, JsonValue> = {};
  const entries = Object.entries(value).slice(0, 20);
  for (const [key, item] of entries) {
    const lowered = key.toLowerCase();
    if (
      lowered.includes("secret") ||
      lowered.includes("token") ||
      lowered.includes("password") ||
      lowered.includes("apikey") ||
      lowered.includes("api_key")
    ) {
      output[key] = "[redacted]";
      continue;
    }
    output[key] = sanitizeValue(item, depth + 1);
  }
  return output;
}

function sanitizeValue(value: JsonValue, depth: number): JsonValue {
  if (typeof value === "string") {
    return value.length > 256 ? value.slice(0, 256) : value;
  }
  if (Array.isArray(value)) {
    return value.slice(0, 20).map((item) => sanitizeValue(item, depth + 1));
  }
  if (value !== null && typeof value === "object") {
    return sanitizeObject(value as JsonObject, depth);
  }
  return value;
}
