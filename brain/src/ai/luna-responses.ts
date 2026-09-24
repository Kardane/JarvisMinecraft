import OpenAI from "openai";
import { toResponseInputItems } from "openai/lib/responses/ResponseInputItems";
import type {
  FunctionTool,
  ResponseInputItem,
} from "openai/resources/responses/responses";

import type {
  ModelStep,
  ModelToolCall,
  ModelTurnInput,
  ToolName,
} from "../core/types.js";
import {
  buildFunctionTools,
  coreToolForAiName,
  translateFunctionCall,
} from "./tool-schemas.js";
import {
  buildLunaInstructions,
  LUNA_MODEL,
  LUNA_REASONING_EFFORT,
  renderConversation,
} from "./prompt.js";
import type { LunaPort, LunaRoutingContext } from "./types.js";

interface PendingCall {
  readonly callId: string;
  readonly coreTool: ToolName;
}

interface RequestState {
  readonly inputItems: ResponseInputItem[];
  pendingCalls: readonly PendingCall[];
  consumedToolEntries: number;
}

export interface LunaMetadata {
  readonly responseId: string;
  readonly model: string;
  readonly toolCalls: number;
}

export class OpenAiLunaPort implements LunaPort {
  readonly modelId = LUNA_MODEL;
  readonly #client: OpenAI;
  readonly #states = new Map<string, RequestState>();
  readonly #metadata = new Map<string, LunaMetadata>();

  constructor(apiKey: string, client?: OpenAI) {
    if (apiKey.trim().length === 0) {
      throw new Error("OPENAI_API_KEY must not be blank.");
    }
    this.#client =
      client ??
      new OpenAI({
        apiKey,
        maxRetries: 0,
        timeout: 30_000,
      });
  }

  async next(
    input: ModelTurnInput,
    routing: LunaRoutingContext,
  ): Promise<ModelStep> {
    const requestId = input.binding.requestId;
    let state = this.#states.get(requestId);
    if (state === undefined) {
      state = {
        inputItems: [
          {
            role: "user",
            content: renderConversation(input),
          },
        ],
        pendingCalls: [],
        consumedToolEntries: 0,
      };
      this.#states.set(requestId, state);
    } else {
      this.appendFunctionOutputs(state, input);
    }

    const tools: FunctionTool[] = buildFunctionTools(input.availableTools);
    const remainingMs = Date.parse(input.deadlineAt) - Date.now();
    if (!Number.isFinite(remainingMs) || remainingMs <= 0) {
      this.clear(requestId);
      throw new Error("Luna request deadline has expired.");
    }

    const response = await this.#client.responses.create(
      {
        model: LUNA_MODEL,
        instructions: buildLunaInstructions(routing),
        input: state.inputItems,
        tools,
        parallel_tool_calls: true,
        reasoning: { effort: LUNA_REASONING_EFFORT },
        store: false,
      },
      {
        maxRetries: 0,
        timeout: Math.max(1, Math.min(remainingMs, 30_000)),
      },
    );

    state.inputItems.push(...toResponseInputItems(response.output));

    const active = new Set<ToolName>(
      input.availableTools.map((descriptor) => descriptor.name),
    );
    const pending: PendingCall[] = [];
    const calls: ModelToolCall[] = [];

    for (const item of response.output) {
      if (item.type !== "function_call") {
        continue;
      }
      const coreTool = coreToolForAiName(item.name);
      const translated = translateFunctionCall(
        item.name,
        item.arguments,
        active,
      );
      pending.push({
        callId: item.call_id,
        coreTool,
      });
      calls.push(translated);
    }

    this.#metadata.set(requestId, {
      responseId: response.id,
      model: response.model,
      toolCalls: calls.length,
    });

    if (calls.length > 0) {
      state.pendingCalls = pending;
      return { kind: "tools", calls };
    }

    const text = response.output_text.trim();
    if (text.length === 0) {
      this.clear(requestId);
      throw new Error("Luna returned neither text nor Tool calls.");
    }

    this.#states.delete(requestId);
    return {
      kind: "final",
      text,
      sessionState: "CONTINUE",
    };
  }

  clear(requestId: string): void {
    this.#states.delete(requestId);
  }

  lastMetadata(requestId: string): LunaMetadata | undefined {
    return this.#metadata.get(requestId);
  }

  private appendFunctionOutputs(
    state: RequestState,
    input: ModelTurnInput,
  ): void {
    const currentToolEntries = input.history.filter(
      (entry) =>
        entry.role === "tool" &&
        entry.requestId === input.binding.requestId,
    );
    const newEntries = currentToolEntries.slice(state.consumedToolEntries);

    if (
      newEntries.length !== state.pendingCalls.length ||
      newEntries.some(
        (entry, index) =>
          entry.role !== "tool" ||
          entry.tool !== state.pendingCalls[index]?.coreTool,
      )
    ) {
      throw new Error("Luna Tool-call/result sequence is inconsistent.");
    }

    for (let index = 0; index < newEntries.length; index += 1) {
      const entry = newEntries[index];
      const pending = state.pendingCalls[index];
      if (entry === undefined || entry.role !== "tool" || pending === undefined) {
        throw new Error("Luna Tool-call/result sequence is incomplete.");
      }
      state.inputItems.push({
        type: "function_call_output",
        call_id: pending.callId,
        output: JSON.stringify(entry.result),
      });
    }

    state.consumedToolEntries = currentToolEntries.length;
    state.pendingCalls = [];
  }
}
