import { choice, TypeSafeClient } from "@typesafe-ai/sdk";

import type {
  JevCategory,
  JevClassification,
  JevClassifierPort,
  JevClassifyInput,
} from "./types.js";

export const JEV_MODEL = "jev-1.13.0" as const;
export const JEV_TIMEOUT_MS = 3_000;

const ROUTES = {
  SERVER_QUERY: "Current server health, TPS/MSPT, player count, or server-wide status.",
  PLAYER_QUERY: "A player lookup, online state, exact identity, position, or nearby players.",
  WORLD_QUERY: "Loaded world/dimension information or location/world context.",
  HISTORY_QUERY: "Historical block/player activity that requires a history provider.",
  REGION_QUERY: "Protected region, region flags, membership, ownership, or build protection.",
  ACTION_REQUEST: "The operator explicitly asks JARVIS to perform a supported server-side action.",
  GENERAL: "General conversation that does not require Minecraft server data or an action.",
  UNCERTAIN: "The intent is ambiguous or does not safely fit another route.",
} as const;

const CATEGORY_SET = new Set<string>(Object.keys(ROUTES));

export class TypeSafeJevClassifier implements JevClassifierPort {
  readonly #client: TypeSafeClient;

  constructor(apiKey: string, client?: TypeSafeClient) {
    if (apiKey.trim().length === 0) {
      throw new Error("TYPESAFE_API_KEY must not be blank.");
    }
    this.#client =
      client ??
      new TypeSafeClient({
        apiKey,
        defaultModel: JEV_MODEL,
        timeout: JEV_TIMEOUT_MS,
        retry: { maxRetries: 0 },
        logLevel: "off",
      });
  }

  async classify(input: JevClassifyInput): Promise<JevClassification> {
    const request = this.#client.systemOne(
      {
        model: JEV_MODEL,
        state: {
          latest_message: input.latestMessage.slice(0, 4_096),
          short_topic: input.shortTopic.slice(0, 2_000),
          capabilities: input.capabilities.slice(0, 64),
        },
        questions: {
          route: choice(
            "Classify this Minecraft server-operator JARVIS request into exactly one route. " +
              "Choose ACTION_REQUEST only when the operator explicitly asks JARVIS to change server state.",
            ROUTES,
          ),
        },
      },
      {
        timeout: JEV_TIMEOUT_MS,
        retry: { maxRetries: 0 },
      },
    );

    const { data, requestId } = await request.withResponse();
    const answer = data.answers.route;
    const category = String(answer.choice);

    if (!CATEGORY_SET.has(category)) {
      throw new Error("Jev returned an unknown route.");
    }
    if (!Number.isFinite(answer.confidence) || answer.confidence < 0 || answer.confidence > 1) {
      throw new Error("Jev returned an invalid confidence value.");
    }

    const probabilities = Object.fromEntries(
      Object.entries(answer.probabilities).map(([key, value]) => {
        const numeric = Number(value);
        if (!CATEGORY_SET.has(key) || !Number.isFinite(numeric) || numeric < 0 || numeric > 1) {
          throw new Error("Jev returned invalid route probabilities.");
        }
        return [key, numeric];
      }),
    ) as Record<JevCategory, number>;

    return {
      category: category as JevCategory,
      confidence: answer.confidence,
      probabilities,
      model: data.model,
      requestId: requestId ?? null,
    };
  }
}
