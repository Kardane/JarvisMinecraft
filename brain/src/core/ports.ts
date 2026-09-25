import type {
  ActorBinding,
  CapabilityDescriptor,
  ChatResponseEnvelope,
  JsonObject,
  ModelStep,
  ModelTurnInput,
  ToolName,
  ToolRequestEnvelope,
  ToolResultEnvelope,
} from "./types.js";

export interface ModelPort {
  readonly modelId: string;
  next(input: ModelTurnInput): Promise<ModelStep>;
}

export interface AdapterPort {
  isRequestBindingActive(binding: ActorBinding): Promise<boolean>;
  executeTool(request: ToolRequestEnvelope): Promise<ToolResultEnvelope>;
  deliverResponse(response: ChatResponseEnvelope): Promise<void>;
}

export interface AuditEvent {
  readonly timestamp: string;
  readonly serverId: string;
  readonly requesterUuid: string;
  readonly requestId: string;
  readonly toolCallId: string;
  readonly actionId: string | null;
  readonly tool: ToolName;
  readonly risk: "READ_ONLY" | "LOW";
  readonly argumentSummary: JsonObject;
  readonly outcome: string;
  readonly source: string;
  readonly latencyMs: number;
  readonly modelId: string;
  readonly fallbackReason: string | null;
  readonly capabilities: readonly CapabilityDescriptor[];
}

export interface AuditPort {
  record(event: AuditEvent): Promise<boolean>;
}

export interface TimePort {
  nowMs(): number;
}

export const systemTime: TimePort = {
  nowMs: () => Date.now(),
};
