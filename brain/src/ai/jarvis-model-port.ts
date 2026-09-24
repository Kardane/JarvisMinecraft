import type { ModelPort } from "../core/ports.js";
import type {
  ModelStep,
  ModelTurnInput,
  ToolDescriptor,
} from "../core/types.js";
import { buildJevInput, isLowConfidence, readOnlyFallbackTools, toolsForHealthyRoute } from "./routing.js";
import { JEV_MODEL, JEV_TIMEOUT_MS } from "./jev-classifier.js";
import { LUNA_MODEL } from "./prompt.js";
import type {
  ConfidencePolicy,
  JevClassification,
  JevClassifierPort,
  LunaPort,
  LunaRoutingContext,
} from "./types.js";

interface RouteState {
  readonly routing: LunaRoutingContext;
  readonly classification: JevClassification | null;
}

export interface JarvisAiModelOptions {
  readonly classifier: JevClassifierPort;
  readonly luna: LunaPort;
  readonly confidencePolicy?: ConfidencePolicy;
  readonly jevTimeoutMs?: number;
}

export class JarvisAiModel implements ModelPort {
  readonly modelId = LUNA_MODEL;
  readonly #classifier: JevClassifierPort;
  readonly #luna: LunaPort;
  readonly #confidencePolicy: ConfidencePolicy;
  readonly #jevTimeoutMs: number;
  readonly #routes = new Map<string, RouteState>();
  readonly #lastRoutes = new Map<string, RouteState>();

  constructor(options: JarvisAiModelOptions) {
    this.#classifier = options.classifier;
    this.#luna = options.luna;
    this.#confidencePolicy = options.confidencePolicy ?? {};
    this.#jevTimeoutMs = options.jevTimeoutMs ?? JEV_TIMEOUT_MS;
    if (!Number.isFinite(this.#jevTimeoutMs) || this.#jevTimeoutMs < 1) {
      throw new Error("Jev timeout must be positive.");
    }
  }

  async next(input: ModelTurnInput): Promise<ModelStep> {
    const requestId = input.binding.requestId;
    let state = this.#routes.get(requestId);
    if (state === undefined) {
      state = await this.classifyRoute(input);
      this.#routes.set(requestId, state);
      this.rememberRoute(requestId, state);
    }

    const routedInput: ModelTurnInput = {
      ...input,
      availableTools: selectTools(input.availableTools, state.routing),
    };

    let step: ModelStep;
    try {
      step = await this.#luna.next(routedInput, state.routing);
    } catch {
      this.#luna.clear(requestId);
      this.#routes.delete(requestId);
      return {
        kind: "final",
        text: "현재 GPT-6 Luna 응답을 완료할 수 없습니다. 서버 상태나 작업 성공 여부를 추측하지 않았습니다.",
        sessionState: "CONTINUE",
      };
    }

    if (step.kind === "tools") {
      const allowed = new Set(routedInput.availableTools.map((tool) => tool.name));
      if (
        step.calls.length === 0 ||
        step.calls.some((call) => !allowed.has(call.tool as never))
      ) {
        this.#luna.clear(requestId);
        this.#routes.delete(requestId);
        return {
          kind: "final",
          text: "요청을 안전한 Tool 범위로 확정하지 못했습니다. 대상을 조금 더 명확하게 말해 주세요.",
          sessionState: "CONTINUE",
        };
      }
      return step;
    }

    this.#routes.delete(requestId);
    this.#luna.clear(requestId);
    return step;
  }

  lastClassification(requestId: string): JevClassification | null | undefined {
    return this.#lastRoutes.get(requestId)?.classification;
  }

  lastRouting(requestId: string): LunaRoutingContext | undefined {
    return this.#lastRoutes.get(requestId)?.routing;
  }

  private async classifyRoute(input: ModelTurnInput): Promise<RouteState> {
    try {
      const classification = await withTimeout(
        this.#classifier.classify(buildJevInput(input)),
        this.#jevTimeoutMs,
      );

      if (classification.model !== JEV_MODEL) {
        throw new Error("Jev model id did not match the pinned model.");
      }

      if (classification.category === "UNCERTAIN") {
        return {
          classification,
          routing: {
            category: classification.category,
            fallbackReason: "JEV_UNCERTAIN",
            originalAvailableTools: input.availableTools,
          },
        };
      }

      if (isLowConfidence(classification, this.#confidencePolicy)) {
        return {
          classification,
          routing: {
            category: classification.category,
            fallbackReason: "JEV_LOW_CONFIDENCE",
            originalAvailableTools: input.availableTools,
          },
        };
      }

      return {
        classification,
        routing: {
          category: classification.category,
          fallbackReason: null,
          originalAvailableTools: input.availableTools,
        },
      };
    } catch {
      return {
        classification: null,
        routing: {
          category: "UNCERTAIN",
          fallbackReason: "JEV_ERROR",
          originalAvailableTools: input.availableTools,
        },
      };
    }
  }

  private rememberRoute(requestId: string, state: RouteState): void {
    this.#lastRoutes.set(requestId, state);
    if (this.#lastRoutes.size <= 128) {
      return;
    }
    const oldest = this.#lastRoutes.keys().next().value as string | undefined;
    if (oldest !== undefined) {
      this.#lastRoutes.delete(oldest);
    }
  }
}

function selectTools(
  available: readonly ToolDescriptor[],
  routing: LunaRoutingContext,
): readonly ToolDescriptor[] {
  if (routing.fallbackReason !== null) {
    return readOnlyFallbackTools(available);
  }
  return toolsForHealthyRoute(routing.category, available);
}

async function withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
  return await new Promise<T>((resolve, reject) => {
    const timer = globalThis.setTimeout(
      () => reject(new Error("Jev classification timed out.")),
      timeoutMs,
    );
    promise.then(
      (value) => {
        globalThis.clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        globalThis.clearTimeout(timer);
        reject(error);
      },
    );
  });
}
