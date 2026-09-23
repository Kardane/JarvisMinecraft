import {
  JarvisAiModel,
  OpenAiLunaPort,
  TypeSafeJevClassifier,
} from "../../brain/dist/ai/index.js";

const openaiKey = process.env.OPENAI_API_KEY;
const typesafeKey = process.env.TYPESAFE_API_KEY;
if (!openaiKey || !typesafeKey) {
  console.error(
    "OPENAI_API_KEY and TYPESAFE_API_KEY are required. Keys are never printed.",
  );
  process.exit(1);
}

const requestId = crypto.randomUUID();
const sessionId = crypto.randomUUID();
const requesterUuid = crypto.randomUUID();

const classifier = new TypeSafeJevClassifier(typesafeKey);
const luna = new OpenAiLunaPort(openaiKey);
const model = new JarvisAiModel({
  classifier,
  luna,
});

let history = [
  {
    role: "user",
    text: "자비스 현재 서버 TPS를 실제 서버 데이터로 알려줘. 추측하지 말고 제공된 조회 Tool을 사용해.",
    requestId,
    at: new Date().toISOString(),
  },
];

let finalStep = null;
let firstToolStep = null;

for (let round = 0; round < 4; round += 1) {
  const step = await model.next(turn(history));
  if (step.kind === "final") {
    finalStep = step;
    break;
  }

  if (firstToolStep === null) {
    firstToolStep = step;
  }

  for (const call of step.calls) {
    if (call.tool !== "get_server_status") {
      throw new Error(
        "Live verification received an unexpected Tool: " + call.tool,
      );
    }
    history = [
      ...history,
      {
        role: "tool",
        tool: call.tool,
        toolCallId: crypto.randomUUID(),
        result: {
          status: "OK",
          data: {
            tps: {
              value: 19.95,
              unit: "tps",
              windowMs: 60_000,
              observedAt: new Date().toISOString(),
              source: "T05LiveFixture",
            },
            mspt: {
              value: 18.4,
              unit: "ms",
              windowMs: 10_000,
              observedAt: new Date().toISOString(),
              source: "T05LiveFixture",
            },
          },
          error: null,
          observedAt: new Date().toISOString(),
          source: "T05LiveFixture",
          truncated: false,
        },
        requestId,
        at: new Date().toISOString(),
      },
    ];
  }
}

if (firstToolStep === null) {
  throw new Error("GPT-6 Luna did not produce the expected Tool call.");
}
if (finalStep === null) {
  throw new Error("GPT-6 Luna did not complete the Tool-result loop.");
}

const classification = model.lastClassification(requestId);
const metadata = luna.lastMetadata(requestId);

console.log(
  JSON.stringify(
    {
      verifiedAt: new Date().toISOString(),
      requestId,
      jev: classification
        ? {
            model: classification.model,
            category: classification.category,
            confidence: classification.confidence,
            requestId: classification.requestId,
          }
        : null,
      luna: metadata ?? null,
      firstToolCalls: firstToolStep.calls.map((call) => call.tool),
      finalText: finalStep.text,
      simulatedAdapterResult: true,
      note:
        "This script validates both live model SDKs and the Luna Tool-call/result loop. Actual Minecraft Adapter E2E remains T10.",
    },
    null,
    2,
  ),
);

function turn(currentHistory) {
  return {
    binding: {
      serverId: "t05-live",
      requesterUuid,
      sessionId,
      requestId,
    },
    requesterName: "LiveVerifier",
    history: currentHistory,
    capabilities: [
      {
        name: "server.status",
        source: "T05LiveFixture",
        version: "1.0",
      },
    ],
    availableTools: [
      {
        name: "get_server_status",
        capability: "server.status",
        stateChanging: false,
        risk: "READ_ONLY",
      },
    ],
    remainingToolCalls: 8,
    remainingModelRounds: 4,
    deadlineAt: new Date(Date.now() + 30_000).toISOString(),
  };
}
