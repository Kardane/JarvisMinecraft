import test from "node:test";
import assert from "node:assert/strict";

import {
  buildFunctionTools,
  buildJevInput,
  JarvisAiModel,
  JEV_MODEL,
  translateFunctionCall,
} from "../../dist/ai/index.js";
import { resolveActiveTools } from "../../dist/core/catalog.js";

const REQUEST = "00000000-0000-4000-8000-000000000101";
const SESSION = "00000000-0000-4000-8000-000000000102";
const ADMIN = "00000000-0000-4000-8000-000000000103";
const TARGET = "00000000-0000-4000-8000-000000000104";

class FakeClassifier {
  constructor(result) {
    this.result = result;
    this.inputs = [];
    this.error = null;
  }

  async classify(input) {
    this.inputs.push(input);
    if (this.error !== null) {
      throw this.error;
    }
    return this.result;
  }
}

class FakeLuna {
  constructor(step) {
    this.modelId = "gpt-6-luna";
    this.step = step;
    this.inputs = [];
    this.routings = [];
    this.cleared = [];
    this.error = null;
  }

  async next(input, routing) {
    this.inputs.push(input);
    this.routings.push(routing);
    if (this.error !== null) {
      throw this.error;
    }
    return typeof this.step === "function"
      ? await this.step(input, routing)
      : this.step;
  }

  clear(requestId) {
    this.cleared.push(requestId);
  }
}

test("healthy SERVER_QUERY exposes only server-route Tools", async () => {
  const classifier = new FakeClassifier(classification("SERVER_QUERY", 0.92));
  const luna = new FakeLuna({ kind: "final", text: "확인했습니다.", sessionState: "CONTINUE" });
  const model = new JarvisAiModel({ classifier, luna });

  await model.next(turn([
    tool("get_server_status", "server.status", false),
    tool("get_online_players", "player.list", false),
    tool("get_player_location", "player.location", false),
    tool("teleport_staff", "staff.self_teleport", true),
  ]));

  assert.deepEqual(
    luna.inputs[0].availableTools.map((item) => item.name),
    ["get_server_status", "get_online_players"],
  );
  assert.equal(luna.routings[0].fallbackReason, null);
});

test("healthy ACTION_REQUEST may expose teleport but only with supporting player Tools", async () => {
  const classifier = new FakeClassifier(classification("ACTION_REQUEST", 0.97));
  const luna = new FakeLuna({
    kind: "tools",
    calls: [{ tool: "teleport_staff", arguments: { targetPlayerUuid: TARGET } }],
  });
  const model = new JarvisAiModel({ classifier, luna });

  const step = await model.next(turn([
    tool("get_server_status", "server.status", false),
    tool("get_player", "player.lookup", false),
    tool("get_player_location", "player.location", false),
    tool("teleport_staff", "staff.self_teleport", true),
  ]));

  assert.equal(step.kind, "tools");
  assert.deepEqual(
    luna.inputs[0].availableTools.map((item) => item.name),
    ["get_player", "get_player_location", "teleport_staff"],
  );
});

test("Jev failure fallback removes every state-changing Tool", async () => {
  const classifier = new FakeClassifier(classification("ACTION_REQUEST", 0.99));
  classifier.error = new Error("429");
  const luna = new FakeLuna({ kind: "final", text: "현재는 조회만 가능합니다.", sessionState: "CONTINUE" });
  const model = new JarvisAiModel({ classifier, luna });

  await model.next(turn([
    tool("get_server_status", "server.status", false),
    tool("get_player_location", "player.location", false),
    tool("teleport_staff", "staff.self_teleport", true),
  ]));

  assert.deepEqual(
    luna.inputs[0].availableTools.map((item) => item.name),
    ["get_server_status", "get_player_location"],
  );
  assert.equal(luna.routings[0].fallbackReason, "JEV_ERROR");
  assert.equal(model.modelId, "gpt-6-luna");
});

test("configured low-confidence policy abstains without enabling changes", async () => {
  const classifier = new FakeClassifier(classification("ACTION_REQUEST", 0.61));
  const luna = new FakeLuna({ kind: "final", text: "안전하게 확인하겠습니다.", sessionState: "CONTINUE" });
  const model = new JarvisAiModel({
    classifier,
    luna,
    confidencePolicy: { abstainBelow: 0.8 },
  });

  await model.next(turn([
    tool("get_player_location", "player.location", false),
    tool("teleport_staff", "staff.self_teleport", true),
  ]));

  assert.deepEqual(
    luna.inputs[0].availableTools.map((item) => item.name),
    ["get_player_location"],
  );
  assert.equal(luna.routings[0].fallbackReason, "JEV_LOW_CONFIDENCE");
});

test("Jev timeout follows the same read-only fallback", async () => {
  const classifier = {
    inputs: [],
    classify(input) {
      this.inputs.push(input);
      return new Promise(() => {});
    },
  };
  const luna = new FakeLuna({ kind: "final", text: "조회만 진행합니다.", sessionState: "CONTINUE" });
  const model = new JarvisAiModel({
    classifier,
    luna,
    jevTimeoutMs: 5,
  });

  await model.next(turn([
    tool("get_server_status", "server.status", false),
    tool("teleport_staff", "staff.self_teleport", true),
  ]));

  assert.equal(luna.routings[0].fallbackReason, "JEV_ERROR");
  assert.deepEqual(
    luna.inputs[0].availableTools.map((item) => item.name),
    ["get_server_status"],
  );
});

test("Luna failure returns a fixed failure response and never swaps models", async () => {
  const classifier = new FakeClassifier(classification("SERVER_QUERY", 0.9));
  const luna = new FakeLuna(null);
  luna.error = new Error("OpenAI unavailable");
  const model = new JarvisAiModel({ classifier, luna });

  const step = await model.next(turn([
    tool("get_server_status", "server.status", false),
  ]));

  assert.equal(step.kind, "final");
  assert.match(step.text, /GPT-6 Luna/);
  assert.match(step.text, /추측하지 않았습니다/);
  assert.equal(model.modelId, "gpt-6-luna");
  assert.deepEqual(luna.cleared, [REQUEST]);
});

test("Jev receives only latest message, short non-Tool topic, and capability names", () => {
  const input = turn([tool("get_server_status", "server.status", false)], [
    {
      role: "user",
      text: "자비스 아까 서버 상태 봤지?",
      requestId: "old",
      at: "2026-09-23T15:00:00Z",
    },
    {
      role: "tool",
      tool: "get_server_status",
      toolCallId: "tool-old",
      result: {
        status: "OK",
        data: { private_detail: "must-not-enter-jev-topic" },
        error: null,
        observedAt: "2026-09-23T15:00:01Z",
        source: "Paper",
        truncated: false,
      },
      requestId: "old",
      at: "2026-09-23T15:00:01Z",
    },
    {
      role: "user",
      text: "지금은 어때?",
      requestId: REQUEST,
      at: "2026-09-23T15:10:00Z",
    },
  ]);

  const state = buildJevInput(input);
  assert.equal(state.latestMessage, "지금은 어때?");
  assert.match(state.shortTopic, /아까 서버 상태/);
  assert.doesNotMatch(state.shortTopic, /private_detail/);
  assert.deepEqual(state.capabilities, ["server.status"]);
});

test("strict Luna schema splits get_player into exact-name and UUID functions", () => {
  const tools = buildFunctionTools([
    tool("get_player", "player.lookup", false),
  ]);

  assert.deepEqual(
    tools.map((item) => item.name),
    ["get_player_by_uuid", "get_player_by_name"],
  );
  for (const item of tools) {
    assert.equal(item.strict, true);
    assert.equal(item.parameters.additionalProperties, false);
  }

  assert.deepEqual(
    translateFunctionCall(
      "get_player_by_uuid",
      JSON.stringify({ playerUuid: TARGET }),
      new Set(["get_player"]),
    ),
    {
      tool: "get_player",
      arguments: { playerUuid: TARGET },
    },
  );

  assert.deepEqual(
    translateFunctionCall(
      "get_player_by_name",
      JSON.stringify({ exactName: "Steve" }),
      new Set(["get_player"]),
    ),
    {
      tool: "get_player",
      arguments: { exactName: "Steve" },
    },
  );
});

test("CMI profile is optional and exposes a strict UUID-only Luna function", () => {
  const active = resolveActiveTools({
    capabilities: [
      { name: "player.cmi_profile", source: "CMI", version: "9.8.9.6" },
    ],
    tools: ["get_cmi_player_info"],
    limits: {
      maxMessageBytes: 65_536,
      maxToolCallsPerRequest: 8,
      maxModelRoundTripsPerRequest: 4,
    },
  });
  assert.deepEqual(active, [tool("get_cmi_player_info", "player.cmi_profile", false)]);

  const [definition] = buildFunctionTools(active);
  assert.equal(definition.name, "get_cmi_player_info");
  assert.equal(definition.strict, true);
  assert.equal(definition.parameters.additionalProperties, false);
  assert.deepEqual(definition.parameters.required, ["playerUuid"]);
  assert.deepEqual(
    translateFunctionCall(
      "get_cmi_player_info",
      JSON.stringify({ playerUuid: TARGET }),
      new Set(["get_cmi_player_info"]),
    ),
    {
      tool: "get_cmi_player_info",
      arguments: { playerUuid: TARGET },
    },
  );
  assert.throws(() =>
    resolveActiveTools({
      capabilities: [],
      tools: ["get_cmi_player_info"],
      limits: {
        maxMessageBytes: 65_536,
        maxToolCallsPerRequest: 8,
        maxModelRoundTripsPerRequest: 4,
      },
    }),
  );
  assert.throws(() =>
    translateFunctionCall(
      "get_cmi_player_info",
      JSON.stringify({ playerUuid: TARGET, includeOffline: true }),
      new Set(["get_cmi_player_info"]),
    ),
  );
});

test("local Luna argument validation rejects unknown fields and out-of-range values", () => {
  assert.throws(() =>
    translateFunctionCall(
      "get_player_location",
      JSON.stringify({ playerUuid: TARGET, command: "/op me" }),
      new Set(["get_player_location"]),
    ),
  );

  assert.throws(() =>
    translateFunctionCall(
      "get_nearby_players",
      JSON.stringify({
        center: {
          worldId: "world",
          x: 0,
          y: 64,
          z: 0,
          yaw: 0,
          pitch: 0,
        },
        radius: 1000,
        limit: 10,
      }),
      new Set(["get_nearby_players"]),
    ),
  );

  assert.throws(() =>
    translateFunctionCall(
      "run_console_command",
      JSON.stringify({ command: "stop" }),
      new Set(),
    ),
  );
});

test("model output cannot escape fallback Tool allowlist", async () => {
  const classifier = new FakeClassifier(classification("UNCERTAIN", 0.4));
  const luna = new FakeLuna({
    kind: "tools",
    calls: [{ tool: "teleport_staff", arguments: { targetPlayerUuid: TARGET } }],
  });
  const model = new JarvisAiModel({ classifier, luna });

  const step = await model.next(turn([
    tool("get_player_location", "player.location", false),
    tool("teleport_staff", "staff.self_teleport", true),
  ]));

  assert.equal(step.kind, "final");
  assert.match(step.text, /안전한 Tool 범위/);
  assert.deepEqual(luna.cleared, [REQUEST]);
});

function classification(category, confidence) {
  const labels = [
    "SERVER_QUERY",
    "PLAYER_QUERY",
    "WORLD_QUERY",
    "HISTORY_QUERY",
    "REGION_QUERY",
    "ACTION_REQUEST",
    "GENERAL",
    "UNCERTAIN",
  ];
  const probabilities = Object.fromEntries(
    labels.map((label) => [label, label === category ? confidence : (1 - confidence) / 7]),
  );
  return {
    category,
    confidence,
    probabilities,
    model: JEV_MODEL,
    requestId: "jev-request-test",
  };
}

function turn(availableTools, history) {
  return {
    binding: {
      serverId: "main",
      requesterUuid: ADMIN,
      sessionId: SESSION,
      requestId: REQUEST,
    },
    requesterName: "Admin",
    history: history ?? [
      {
        role: "user",
        text: "자비스 지금 서버 상태 어때?",
        requestId: REQUEST,
        at: "2026-09-23T15:10:00Z",
      },
    ],
    capabilities: [...new Set(availableTools.map((item) => item.capability))].map((name) => ({
      name,
      source: "FakeAdapter",
      version: "1.0",
    })),
    availableTools,
    remainingToolCalls: 8,
    remainingModelRounds: 4,
    deadlineAt: new Date(Date.now() + 30_000).toISOString(),
  };
}

function tool(name, capability, stateChanging) {
  return {
    name,
    capability,
    stateChanging,
    risk: stateChanging ? "LOW" : "READ_ONLY",
  };
}
