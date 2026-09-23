import type {
  ModelStep,
  ModelTurnInput,
  ToolDescriptor,
} from "../core/types.js";

export type JevCategory =
  | "SERVER_QUERY"
  | "PLAYER_QUERY"
  | "WORLD_QUERY"
  | "HISTORY_QUERY"
  | "REGION_QUERY"
  | "ACTION_REQUEST"
  | "GENERAL"
  | "UNCERTAIN";

export interface JevClassifyInput {
  readonly latestMessage: string;
  readonly shortTopic: string;
  readonly capabilities: readonly string[];
}

export interface JevClassification {
  readonly category: JevCategory;
  readonly confidence: number;
  readonly probabilities: Readonly<Record<JevCategory, number>>;
  readonly model: string;
  readonly requestId: string | null;
}

export interface JevClassifierPort {
  classify(input: JevClassifyInput): Promise<JevClassification>;
}

export interface LunaRoutingContext {
  readonly category: JevCategory;
  readonly fallbackReason:
    | "JEV_ERROR"
    | "JEV_UNCERTAIN"
    | "JEV_LOW_CONFIDENCE"
    | null;
  readonly originalAvailableTools: readonly ToolDescriptor[];
}

export interface LunaPort {
  readonly modelId: "gpt-6-luna";
  next(input: ModelTurnInput, routing: LunaRoutingContext): Promise<ModelStep>;
  clear(requestId: string): void;
}

export interface ConfidencePolicy {
  readonly abstainBelow?: number;
}
