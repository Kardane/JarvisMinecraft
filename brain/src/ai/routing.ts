import type { ModelTurnInput, ToolDescriptor, ToolName } from "../core/types.js";
import type {
  ConfidencePolicy,
  JevCategory,
  JevClassifyInput,
  JevClassification,
} from "./types.js";

const ROUTE_TOOLS: Record<JevCategory, ReadonlySet<ToolName>> = {
  SERVER_QUERY: new Set([
    "get_server_status",
    "get_online_players",
  ]),
  PLAYER_QUERY: new Set([
    "get_online_players",
    "get_player",
    "get_player_location",
    "get_nearby_players",
  ]),
  WORLD_QUERY: new Set([
    "get_world_info",
    "get_nearby_players",
  ]),
  HISTORY_QUERY: new Set([
    "lookup_area_history",
    "lookup_player_history",
  ]),
  REGION_QUERY: new Set([
    "get_regions_at_location",
    "get_region_info",
    "check_build_permission",
  ]),
  ACTION_REQUEST: new Set([
    "get_player",
    "get_player_location",
    "teleport_staff",
  ]),
  GENERAL: new Set(),
  UNCERTAIN: new Set(),
};

export function buildJevInput(input: ModelTurnInput): JevClassifyInput {
  const latestUser = [...input.history]
    .reverse()
    .find((entry) => entry.role === "user");

  const shortTopic = input.history
    .filter((entry) => entry.role !== "tool")
    .slice(-6)
    .map((entry) => {
      if (entry.role === "user" || entry.role === "assistant") {
        return entry.role + ": " + entry.text.slice(0, 320);
      }
      return "";
    })
    .filter((value) => value.length > 0)
    .join("\n");

  return {
    latestMessage: latestUser?.role === "user" ? latestUser.text : "",
    shortTopic,
    capabilities: input.capabilities.map((item) => item.name),
  };
}

export function isLowConfidence(
  classification: JevClassification,
  policy: ConfidencePolicy,
): boolean {
  const threshold = policy.abstainBelow;
  if (threshold === undefined) {
    return false;
  }
  if (!Number.isFinite(threshold) || threshold < 0 || threshold > 1) {
    throw new Error("Jev confidence threshold must be between 0 and 1.");
  }
  return classification.confidence < threshold;
}

export function toolsForHealthyRoute(
  category: JevCategory,
  available: readonly ToolDescriptor[],
): readonly ToolDescriptor[] {
  const allowed = ROUTE_TOOLS[category];
  return available.filter((tool) => allowed.has(tool.name));
}

export function readOnlyFallbackTools(
  available: readonly ToolDescriptor[],
): readonly ToolDescriptor[] {
  return available.filter((tool) => !tool.stateChanging);
}
