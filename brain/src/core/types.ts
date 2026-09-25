export type ErrorCode =
  | "UNAUTHORIZED"
  | "INVALID_ARGUMENT"
  | "UNSUPPORTED"
  | "NOT_FOUND"
  | "AMBIGUOUS_TARGET"
  | "BUSY"
  | "TIMEOUT"
  | "PROVIDER_UNAVAILABLE"
  | "CANCELLED"
  | "OUTCOME_UNKNOWN"
  | "INTERNAL";

export type ToolName =
  | "get_server_status"
  | "get_online_players"
  | "get_player"
  | "get_player_location"
  | "get_nearby_players"
  | "get_world_info"
  | "teleport_staff"
  | "lookup_area_history"
  | "lookup_player_history"
  | "get_regions_at_location"
  | "get_region_info"
  | "check_build_permission"
  | "get_cmi_player_info";

export type JsonPrimitive = string | number | boolean | null;
export type JsonValue =
  | JsonPrimitive
  | readonly JsonValue[]
  | { readonly [key: string]: JsonValue };
export type JsonObject = { readonly [key: string]: JsonValue };

export interface ErrorObject {
  readonly code: ErrorCode;
  readonly message: string;
  readonly retryable: boolean;
  readonly details: Readonly<Record<string, JsonPrimitive>>;
}

export interface CapabilityDescriptor {
  readonly name: string;
  readonly source: string;
  readonly version: string | null;
}

export interface CapabilitySnapshot {
  readonly capabilities: readonly CapabilityDescriptor[];
  readonly tools: readonly string[];
  readonly limits: {
    readonly maxMessageBytes: number;
    readonly maxToolCallsPerRequest: number;
    readonly maxModelRoundTripsPerRequest: number;
  };
}

export interface ToolDescriptor {
  readonly name: ToolName;
  readonly capability: string;
  readonly stateChanging: boolean;
  readonly risk: "READ_ONLY" | "LOW";
}

export interface ActorBinding {
  readonly serverId: string;
  readonly requesterUuid: string;
  readonly sessionId: string;
  readonly requestId: string;
}

export interface ChatMessageEnvelope {
  readonly protocolVersion: "1.0";
  readonly type: "chat.message";
  readonly messageId: string;
  readonly requestId: string;
  readonly serverId: string;
  readonly sessionId: string;
  readonly requesterUuid: string;
  readonly sentAt: string;
  readonly deadlineAt: string;
  readonly payload: {
    readonly requesterName: string;
    readonly text: string;
    readonly mode: "DIRECT" | "FOLLOW_UP";
  };
}

export interface ChatResponseEnvelope {
  readonly protocolVersion: "1.0";
  readonly type: "chat.response";
  readonly messageId: string;
  readonly requestId: string;
  readonly serverId: string;
  readonly sessionId: string;
  readonly requesterUuid: string;
  readonly sentAt: string;
  readonly deadlineAt: string;
  readonly payload: {
    readonly text: string;
    readonly final: boolean;
    readonly sessionState: "CONTINUE" | "END";
  };
}

export interface ToolRequestEnvelope {
  readonly protocolVersion: "1.0";
  readonly type: "tool.request";
  readonly messageId: string;
  readonly requestId: string;
  readonly serverId: string;
  readonly sessionId: string;
  readonly requesterUuid: string;
  readonly sentAt: string;
  readonly deadlineAt: string;
  readonly payload: {
    readonly tool: ToolName;
    readonly arguments: JsonObject;
  };
  readonly toolCallId: string;
  readonly actionId: string | null;
}

export interface ToolResult {
  readonly status: "OK" | "EMPTY" | "ERROR" | "UNSUPPORTED";
  readonly data: JsonValue | null;
  readonly error: ErrorObject | null;
  readonly observedAt: string;
  readonly source: string;
  readonly truncated: boolean;
}

export interface ToolResultEnvelope {
  readonly protocolVersion: "1.0";
  readonly type: "tool.result";
  readonly messageId: string;
  readonly requestId: string;
  readonly serverId: string;
  readonly sessionId: string;
  readonly requesterUuid: string;
  readonly sentAt: string;
  readonly deadlineAt: string;
  readonly payload: {
    readonly tool: ToolName;
    readonly result: ToolResult;
  };
  readonly toolCallId: string;
  readonly actionId: string | null;
}

export type ConversationEntry =
  | {
      readonly role: "user";
      readonly text: string;
      readonly requestId: string;
      readonly at: string;
    }
  | {
      readonly role: "assistant";
      readonly text: string;
      readonly requestId: string;
      readonly at: string;
    }
  | {
      readonly role: "tool";
      readonly tool: ToolName;
      readonly toolCallId: string;
      readonly result: ToolResult;
      readonly requestId: string;
      readonly at: string;
    };

export interface ModelToolCall {
  readonly tool: string;
  readonly arguments: JsonObject;
}

export type ModelStep =
  | {
      readonly kind: "final";
      readonly text: string;
      readonly sessionState: "CONTINUE" | "END";
    }
  | {
      readonly kind: "tools";
      readonly calls: readonly ModelToolCall[];
    };

export interface ModelTurnInput {
  readonly binding: ActorBinding;
  readonly requesterName: string;
  readonly history: readonly ConversationEntry[];
  readonly capabilities: readonly CapabilityDescriptor[];
  readonly availableTools: readonly ToolDescriptor[];
  readonly remainingToolCalls: number;
  readonly remainingModelRounds: number;
  readonly deadlineAt: string;
}

export type CoreOutcome =
  | {
      readonly status: "RESPONDED";
      readonly response: ChatResponseEnvelope;
    }
  | {
      readonly status: "REJECTED";
      readonly code: ErrorCode;
    };
