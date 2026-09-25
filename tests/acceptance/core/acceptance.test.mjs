import test from "node:test";
import assert from "node:assert/strict";

import {
  BrainCore,
  RequestScheduler,
} from "../../../brain/dist/core/index.js";
import {
  JarvisAiModel,
  JEV_MODEL,
} from "../../../brain/dist/ai/index.js";
import {
  JsonlAuditSink,
} from "../../../brain/dist/ops/index.js";

const ADMIN = uuid(1001);
const OTHER = uuid(1002);
const TARGET = uuid(1003);

test("A01: non-OP never reaches model/Tool/response; OP response stays requester-bound", async () => {
  const adapter = new FakeAdapter();
  const model = new SequenceModel([{ kind: "final", text: "ok", sessionState: "CONTINUE" }]);
  const core = makeCore(adapter, model, new AcceptAudit(), ["get_server_status"]);

  const rejected = await core.handleChat("conn-main", chat({
    requesterUuid: OTHER,
    sessionId: uuid(1101),
    requestId: uuid(1201),
    messageId: uuid(1301),
    text: "자비스 서버 상태",
  }));
  assert.deepEqual(rejected, { status: "REJECTED", code: "UNAUTHORIZED" });
  assert.equal(model.inputs.length, 0);
  assert.equal(adapter.responses.length, 0);

  adapter.operators.add(ADMIN);
  const accepted = await core.handleChat("conn-main", chat({
    requesterUuid: ADMIN,
    sessionId: uuid(1102),
    requestId: uuid(1202),
    messageId: uuid(1302),
    text: "자비스 안녕",
  }));
  assert.equal(accepted.status, "RESPONDED");
  assert.equal(adapter.responses.length, 1);
  assert.equal(adapter.responses[0].requesterUuid, ADMIN);
});

test("A02: deop after model decision prevents Tool execution and response", async () => {
  const adapter = new FakeAdapter();
  adapter.operators.add(ADMIN);
  const model = {
    modelId: "acceptance-model",
    async next() {
      adapter.operators.delete(ADMIN);
      return {
        kind: "tools",
        calls: [{ tool: "get_server_status", arguments: {} }],
      };
    },
  };
  const core = makeCore(adapter, model, new AcceptAudit(), ["get_server_status"]);
  const outcome = await core.handleChat("conn-main", chat({
    requesterUuid: ADMIN,
    sessionId: uuid(1103),
    requestId: uuid(1203),
    messageId: uuid(1303),
    text: "자비스 서버 상태",
  }));

  assert.deepEqual(outcome, { status: "REJECTED", code: "UNAUTHORIZED" });
  assert.equal(adapter.toolRequests.length, 0);
  assert.equal(adapter.responses.length, 0);
});

test("A03: follow-up expires after 120 seconds", async () => {
  const time = new ManualTime();
  const adapter = new FakeAdapter();
  adapter.operators.add(ADMIN);
  const model = new SequenceModel([
    { kind: "final", text: "start", sessionState: "CONTINUE" },
  ]);
  const core = makeCore(adapter, model, new AcceptAudit(), ["get_server_status"], time);
  const sessionId = uuid(1104);

  await core.handleChat("conn-main", chat({
    time,
    requesterUuid: ADMIN,
    sessionId,
    requestId: uuid(1204),
    messageId: uuid(1304),
    text: "자비스 시작",
  }));

  time.advance(120_001);
  const outcome = await core.handleChat("conn-main", chat({
    time,
    requesterUuid: ADMIN,
    sessionId,
    requestId: uuid(1205),
    messageId: uuid(1305),
    mode: "FOLLOW_UP",
    text: "후속",
  }));

  assert.deepEqual(outcome, { status: "REJECTED", code: "CANCELLED" });
});

test("A04: same requester name on two servers remains server/UUID/session isolated", async () => {
  const adapterA = new FakeAdapter();
  const adapterB = new FakeAdapter();
  adapterA.operators.add(ADMIN);
  adapterB.operators.add(OTHER);
  const model = new SequenceModel([
    { kind: "final", text: "a", sessionState: "CONTINUE" },
    { kind: "final", text: "b", sessionState: "CONTINUE" },
  ]);
  const core = new BrainCore({ model, audit: new AcceptAudit() });
  core.registerConnection(connection("conn-a", "server-a", adapterA, ["get_server_status"]));
  core.registerConnection(connection("conn-b", "server-b", adapterB, ["get_server_status"]));

  await core.handleChat("conn-a", chat({
    serverId: "server-a",
    requesterUuid: ADMIN,
    requesterName: "SameName",
    sessionId: uuid(1105),
    requestId: uuid(1206),
    messageId: uuid(1306),
    text: "자비스 A",
  }));
  await core.handleChat("conn-b", chat({
    serverId: "server-b",
    requesterUuid: OTHER,
    requesterName: "SameName",
    sessionId: uuid(1106),
    requestId: uuid(1207),
    messageId: uuid(1307),
    text: "자비스 B",
  }));

  assert.equal(model.inputs[0].binding.serverId, "server-a");
  assert.equal(model.inputs[0].binding.requesterUuid, ADMIN);
  assert.equal(model.inputs[1].binding.serverId, "server-b");
  assert.equal(model.inputs[1].binding.requesterUuid, OTHER);
  assert.equal(
    model.inputs[1].history.some((entry) => "text" in entry && entry.text === "자비스 A"),
    false,
  );
});

test("A05: observed metrics and state-changing teleport preserve units, timestamps and actionId", async () => {
  const adapter = new FakeAdapter();
  adapter.operators.add(ADMIN);
  adapter.onTool = async (request) => {
    if (request.payload.tool === "get_server_status") {
      return toolResult(request, {
        tps: { value: 19.9, unit: "tps", windowMs: 60_000, observedAt: nowIso(), source: "FakeServer" },
        mspt: { value: 12.3, unit: "ms", windowMs: null, observedAt: nowIso(), source: "FakeServer" },
      });
    }
    return toolResult(request, {
      requesterUuid: ADMIN,
      targetPlayerUuid: TARGET,
      fromWorldId: "world",
      toWorldId: "world",
      completed: true,
    });
  };

  let round = 0;
  const model = {
    modelId: "acceptance-model",
    async next(input) {
      round += 1;
      if (round === 1) {
        return { kind: "tools", calls: [{ tool: "get_server_status", arguments: {} }] };
      }
      if (round === 2) {
        const result = input.history.find((entry) => entry.role === "tool")?.result;
        assert.equal(result.data.tps.unit, "tps");
        assert.ok(Date.parse(result.data.tps.observedAt));
        return { kind: "tools", calls: [{ tool: "teleport_staff", arguments: { targetPlayerUuid: TARGET } }] };
      }
      return { kind: "final", text: "done", sessionState: "CONTINUE" };
    },
  };
  const audit = new AcceptAudit();
  const core = makeCore(adapter, model, audit, ["get_server_status", "teleport_staff"]);
  const outcome = await core.handleChat("conn-main", chat({
    requesterUuid: ADMIN,
    sessionId: uuid(1107),
    requestId: uuid(1208),
    messageId: uuid(1308),
    text: "자비스 상태 확인하고 나를 보내줘",
  }));

  assert.equal(outcome.status, "RESPONDED");
  assert.equal(adapter.toolRequests.length, 2);
  assert.equal(adapter.toolRequests[0].actionId, null);
  assert.match(adapter.toolRequests[1].actionId, /^[0-9a-f-]{36}$/i);
  assert.equal(audit.events.filter((event) => event.outcome === "PRE_EXECUTION").length, 1);
});

test("A06/A12: unregistered or unavailable Tool never executes", async () => {
  for (const tool of ["run_console_command", "lookup_area_history"]) {
    const adapter = new FakeAdapter();
    adapter.operators.add(ADMIN);
    const model = new SequenceModel([
      {
        kind: "tools",
        calls: [{ tool, arguments: {} }],
      },
    ]);
    const core = makeCore(adapter, model, new AcceptAudit(), ["get_server_status"]);
    const outcome = await core.handleChat("conn-main", chat({
      requesterUuid: ADMIN,
      sessionId: uuid(tool === "run_console_command" ? 1108 : 1109),
      requestId: uuid(tool === "run_console_command" ? 1209 : 1210),
      messageId: uuid(tool === "run_console_command" ? 1309 : 1310),
      text: "자비스 공격 입력",
    }));

    assert.equal(outcome.status, "RESPONDED");
    assert.equal(adapter.toolRequests.length, 0);
  }
});

test("A07: uncertain state-changing outcome is not automatically retried", async () => {
  const adapter = new FakeAdapter();
  adapter.operators.add(ADMIN);
  adapter.onTool = async () => await new Promise(() => {});
  const model = new SequenceModel([
    {
      kind: "tools",
      calls: [{ tool: "teleport_staff", arguments: { targetPlayerUuid: TARGET } }],
    },
  ]);
  const audit = new AcceptAudit();
  const core = new BrainCore({ model, audit });
  core.registerConnection(connection("conn-main", "main", adapter, ["teleport_staff"]));

  const started = Date.now();
  const outcome = await core.handleChat("conn-main", chat({
    requesterUuid: ADMIN,
    sessionId: uuid(1110),
    requestId: uuid(1211),
    messageId: uuid(1311),
    text: "자비스 나 보내줘",
    deadlineMs: 120,
  }));

  assert.equal(outcome.status, "RESPONDED");
  assert.equal(adapter.toolRequests.length, 1);
  assert.ok(Date.now() - started < 2_000);
  assert.match(adapter.responses[0].payload.text, /자동으로 다시 실행하지 않았습니다/);
});

test("A08: Jev error fallback exposes read-only Tools only and does not swap Luna model", async () => {
  const classifier = {
    async classify() {
      throw new Error("429");
    },
  };
  const seen = [];
  const luna = {
    modelId: "gpt-6-luna",
    async next(input, routing) {
      seen.push({ input, routing });
      return { kind: "final", text: "조회 전용", sessionState: "CONTINUE" };
    },
    clear() {},
  };
  const model = new JarvisAiModel({ classifier, luna, jevTimeoutMs: 50 });
  const step = await model.next({
    binding: {
      serverId: "main",
      requesterUuid: ADMIN,
      sessionId: uuid(1111),
      requestId: uuid(1212),
    },
    requesterName: "Admin",
    history: [{
      role: "user",
      text: "자비스 뭔가 해줘",
      requestId: uuid(1212),
      at: nowIso(),
    }],
    capabilities: [
      { name: "server.status", source: "Fake", version: "1" },
      { name: "staff.self_teleport", source: "Fake", version: "1" },
    ],
    availableTools: [
      { name: "get_server_status", capability: "server.status", stateChanging: false, risk: "READ_ONLY" },
      { name: "teleport_staff", capability: "staff.self_teleport", stateChanging: true, risk: "LOW" },
    ],
    remainingToolCalls: 8,
    remainingModelRounds: 4,
    deadlineAt: new Date(Date.now() + 5_000).toISOString(),
  });

  assert.equal(step.kind, "final");
  assert.equal(model.modelId, "gpt-6-luna");
  assert.equal(seen[0].routing.fallbackReason, "JEV_ERROR");
  assert.deepEqual(seen[0].input.availableTools.map((tool) => tool.name), ["get_server_status"]);
  assert.equal(JEV_MODEL, "jev-1.13.0");
});

test("A11: scheduler holds 4 active/server, 16 global queued, 2 queued/session", async () => {
  const scheduler = new RequestScheduler();
  const deferred = new Deferred();
  const active = [];
  for (let i = 0; i < 4; i += 1) {
    active.push(scheduler.submit("main", "active-" + i, async () => await deferred.promise));
  }
  await tick();
  const queued = [];
  for (let i = 0; i < 16; i += 1) {
    queued.push(scheduler.submit("main", "queued-" + i, async () => i));
  }
  assert.equal(scheduler.snapshot().activeByServer.main, 4);
  assert.equal(scheduler.snapshot().queuedTotal, 16);
  await assert.rejects(scheduler.submit("main", "overflow", async () => 99), (error) => error?.code === "BUSY");

  const perSession = new RequestScheduler();
  const same = new Deferred();
  const p1 = perSession.submit("s", "one", async () => await same.promise);
  await tick();
  const p2 = perSession.submit("s", "one", async () => 2);
  const p3 = perSession.submit("s", "one", async () => 3);
  await assert.rejects(perSession.submit("s", "one", async () => 4), (error) => error?.code === "BUSY");
  same.resolve(1);
  await Promise.all([p1, p2, p3]);

  deferred.resolve("ok");
  await Promise.all(active);
  await Promise.all(queued);
});

test("A13: audit I/O failure makes mutation fail closed", async () => {
  const fileSystem = {
    async ensureDirectory() {},
    async list() { return []; },
    async append() { throw new Error("disk full"); },
    async remove() {},
  };
  const audit = new JsonlAuditSink({
    directory: "/audit",
    maxFileBytes: 4_096,
    maxTotalBytes: 8_192,
    fileSystem,
  });
  const adapter = new FakeAdapter();
  adapter.operators.add(ADMIN);
  const model = new SequenceModel([
    {
      kind: "tools",
      calls: [{ tool: "teleport_staff", arguments: { targetPlayerUuid: TARGET } }],
    },
  ]);
  const core = makeCore(adapter, model, audit, ["teleport_staff"]);
  const outcome = await core.handleChat("conn-main", chat({
    requesterUuid: ADMIN,
    sessionId: uuid(1112),
    requestId: uuid(1213),
    messageId: uuid(1312),
    text: "자비스 나 보내줘",
  }));

  assert.equal(outcome.status, "RESPONDED");
  assert.equal(adapter.toolRequests.length, 0);
  assert.equal(audit.health().status, "UNHEALTHY");
});

class FakeAdapter {
  constructor() {
    this.operators = new Set();
    this.toolRequests = [];
    this.responses = [];
    this.onTool = null;
  }
  async isCurrentOperator(binding) {
    return this.operators.has(binding.requesterUuid);
  }
  async executeTool(request) {
    this.toolRequests.push(request);
    if (this.onTool) return await this.onTool(request);
    return toolResult(request, { ok: true });
  }
  async deliverResponse(response) {
    this.responses.push(response);
  }
}

class SequenceModel {
  constructor(steps) {
    this.modelId = "acceptance-model";
    this.steps = steps;
    this.inputs = [];
  }
  async next(input) {
    this.inputs.push(input);
    const step = this.steps[Math.min(this.inputs.length - 1, this.steps.length - 1)];
    return step;
  }
}

class AcceptAudit {
  constructor() { this.events = []; }
  async record(event) {
    this.events.push(event);
    return true;
  }
}

class ManualTime {
  constructor() { this.value = Date.now(); }
  nowMs() { return this.value; }
  advance(ms) { this.value += ms; }
}

class Deferred {
  constructor() {
    this.promise = new Promise((resolve, reject) => {
      this.resolve = resolve;
      this.reject = reject;
    });
  }
}

function makeCore(adapter, model, audit, tools, time) {
  const core = new BrainCore({ model, audit, ...(time ? { time } : {}) });
  core.registerConnection(connection("conn-main", "main", adapter, tools));
  return core;
}

function connection(connectionId, serverId, adapter, tools) {
  const capabilityByTool = {
    get_server_status: "server.status",
    get_online_players: "player.list",
    get_player: "player.lookup",
    get_player_location: "player.location",
    get_nearby_players: "player.nearby",
    get_world_info: "world.info",
    teleport_staff: "staff.self_teleport",
    lookup_area_history: "history.lookup",
    lookup_player_history: "history.lookup",
    get_regions_at_location: "region.lookup",
    get_region_info: "region.lookup",
    check_build_permission: "region.protection",
  };
  return {
    connectionId,
    serverId,
    adapter,
    capabilities: {
      capabilities: [...new Set(tools.map((tool) => capabilityByTool[tool]))].map((name) => ({
        name,
        source: "Acceptance",
        version: "1.0",
      })),
      tools,
      limits: {
        maxMessageBytes: 65_536,
        maxToolCallsPerRequest: 8,
        maxModelRoundTripsPerRequest: 4,
      },
    },
  };
}

function chat(options) {
  const now = options.time?.nowMs() ?? Date.now();
  return {
    protocolVersion: "1.0",
    type: "chat.message",
    messageId: options.messageId,
    requestId: options.requestId,
    serverId: options.serverId ?? "main",
    sessionId: options.sessionId,
    requesterUuid: options.requesterUuid,
    sentAt: new Date(now).toISOString(),
    deadlineAt: new Date(now + (options.deadlineMs ?? 30_000)).toISOString(),
    payload: {
      requesterName: options.requesterName ?? "SameName",
      text: options.text,
      mode: options.mode ?? "DIRECT",
    },
  };
}

function toolResult(request, data) {
  return {
    protocolVersion: "1.0",
    type: "tool.result",
    messageId: crypto.randomUUID(),
    requestId: request.requestId,
    serverId: request.serverId,
    sessionId: request.sessionId,
    requesterUuid: request.requesterUuid,
    sentAt: nowIso(),
    deadlineAt: request.deadlineAt,
    payload: {
      tool: request.payload.tool,
      result: {
        status: "OK",
        data,
        error: null,
        observedAt: nowIso(),
        source: "Acceptance",
        truncated: false,
      },
    },
    toolCallId: request.toolCallId,
    actionId: request.actionId,
  };
}

function nowIso() { return new Date().toISOString(); }
function uuid(value) { return "00000000-0000-4000-8000-" + String(value).padStart(12, "0"); }
function tick() { return new Promise((resolve) => setTimeout(resolve, 0)); }
