import test from "node:test";
import assert from "node:assert/strict";

import {
  BrainCore,
  RequestScheduler,
} from "../../dist/core/index.js";

const BASE_TIME = Date.parse("2026-09-23T15:10:00Z");
const ADMIN = uuid(101);
const OTHER = uuid(102);
const TARGET = uuid(103);

class ManualTime {
  constructor(value = BASE_TIME) {
    this.value = value;
  }

  nowMs() {
    return this.value;
  }

  advance(ms) {
    this.value += ms;
  }
}

class FakeAudit {
  constructor(accept = true) {
    this.accept = accept;
    this.events = [];
  }

  async record(event) {
    this.events.push(event);
    return this.accept;
  }
}

class FakeAdapter {
  constructor(time) {
    this.time = time;
    this.operators = new Set();
    this.bindingChecks = [];
    this.toolRequests = [];
    this.responses = [];
    this.onTool = null;
  }

  async isRequestBindingActive(binding) {
    this.bindingChecks.push(binding);
    return this.operators.has(binding.requesterUuid);
  }

  async executeTool(request) {
    this.toolRequests.push(request);
    if (this.onTool !== null) {
      return await this.onTool(request);
    }
    return toolResult(request, this.time.nowMs());
  }

  async deliverResponse(response) {
    this.responses.push(response);
  }
}

class FakeModel {
  constructor(handler) {
    this.modelId = "fake-model";
    this.handler = handler;
    this.inputs = [];
  }

  async next(input) {
    this.inputs.push(input);
    return await this.handler(input, this.inputs.length);
  }
}

class Deferred {
  constructor() {
    this.promise = new Promise((resolve, reject) => {
      this.resolve = resolve;
      this.reject = reject;
    });
  }
}

test("non-OP chat never reaches model or response delivery", async () => {
  const time = new ManualTime();
  const adapter = new FakeAdapter(time);
  const model = new FakeModel(async () => finalStep("should not run"));
  const core = makeCore(time, adapter, model, new FakeAudit(), ["get_player_location"]);

  const outcome = await core.handleChat("conn-main", chat(time, {
    requesterUuid: ADMIN,
    sessionId: uuid(201),
    requestId: uuid(301),
    messageId: uuid(401),
    mode: "DIRECT",
    text: "자비스 Steve 어디 있어?",
  }));

  assert.deepEqual(outcome, { status: "REJECTED", code: "UNAUTHORIZED" });
  assert.equal(model.inputs.length, 0);
  assert.equal(adapter.toolRequests.length, 0);
  assert.equal(adapter.responses.length, 0);
});

test("session history is isolated by server, actor and session id", async () => {
  const time = new ManualTime();
  const adapter = new FakeAdapter(time);
  adapter.operators.add(ADMIN);
  adapter.operators.add(OTHER);

  const model = new FakeModel(async (_input, call) => finalStep("reply-" + call));
  const audit = new FakeAudit();
  const core = makeCore(time, adapter, model, audit, ["get_player_location"]);

  const session = uuid(202);
  await core.handleChat("conn-main", chat(time, {
    requesterUuid: ADMIN,
    sessionId: session,
    requestId: uuid(302),
    messageId: uuid(402),
    mode: "DIRECT",
    text: "자비스 첫 질문",
  }));

  time.advance(1_000);
  await core.handleChat("conn-main", chat(time, {
    requesterUuid: ADMIN,
    sessionId: session,
    requestId: uuid(303),
    messageId: uuid(403),
    mode: "FOLLOW_UP",
    text: "두 번째 질문",
  }));

  time.advance(1_000);
  await core.handleChat("conn-main", chat(time, {
    requesterUuid: OTHER,
    sessionId: uuid(203),
    requestId: uuid(304),
    messageId: uuid(404),
    mode: "DIRECT",
    text: "자비스 다른 운영자 질문",
  }));

  assert.equal(model.inputs.length, 3);
  const secondHistory = model.inputs[1].history;
  assert.ok(secondHistory.some((entry) => entry.role === "user" && entry.text === "자비스 첫 질문"));
  assert.ok(secondHistory.some((entry) => entry.role === "assistant" && entry.text === "reply-1"));
  assert.ok(secondHistory.some((entry) => entry.role === "user" && entry.text === "두 번째 질문"));

  const otherHistory = model.inputs[2].history;
  assert.equal(
    otherHistory.some((entry) => "text" in entry && entry.text.includes("첫 질문")),
    false,
  );
  assert.equal(adapter.responses[0].requesterUuid, ADMIN);
  assert.equal(adapter.responses[2].requesterUuid, OTHER);
});

test("follow-up is rejected after the 120 second session TTL", async () => {
  const time = new ManualTime();
  const adapter = new FakeAdapter(time);
  adapter.operators.add(ADMIN);
  const model = new FakeModel(async () => finalStep("ok"));
  const core = makeCore(time, adapter, model, new FakeAudit(), ["get_player_location"]);
  const sessionId = uuid(204);

  await core.handleChat("conn-main", chat(time, {
    requesterUuid: ADMIN,
    sessionId,
    requestId: uuid(305),
    messageId: uuid(405),
    mode: "DIRECT",
    text: "자비스 시작",
  }));
  assert.equal(model.inputs.length, 1);

  time.advance(120_001);
  const outcome = await core.handleChat("conn-main", chat(time, {
    requesterUuid: ADMIN,
    sessionId,
    requestId: uuid(306),
    messageId: uuid(406),
    mode: "FOLLOW_UP",
    text: "아직 듣고 있어?",
  }));

  assert.deepEqual(outcome, { status: "REJECTED", code: "CANCELLED" });
  assert.equal(model.inputs.length, 1);
});

test("deop between model decision and Tool execution blocks the Tool and response", async () => {
  const time = new ManualTime();
  const adapter = new FakeAdapter(time);
  adapter.operators.add(ADMIN);

  const model = new FakeModel(async () => {
    adapter.operators.delete(ADMIN);
    return {
      kind: "tools",
      calls: [{ tool: "get_player_location", arguments: { playerUuid: TARGET } }],
    };
  });
  const core = makeCore(time, adapter, model, new FakeAudit(), ["get_player_location"]);

  const outcome = await core.handleChat("conn-main", chat(time, {
    requesterUuid: ADMIN,
    sessionId: uuid(205),
    requestId: uuid(307),
    messageId: uuid(407),
    mode: "DIRECT",
    text: "자비스 Steve 위치 알려줘",
  }));

  assert.deepEqual(outcome, { status: "REJECTED", code: "UNAUTHORIZED" });
  assert.equal(adapter.toolRequests.length, 0);
  assert.equal(adapter.responses.length, 0);
});

test("inactive and unregistered Tools are never sent to the Adapter", async () => {
  for (const tool of ["teleport_staff", "run_console_command"]) {
    const time = new ManualTime();
    const adapter = new FakeAdapter(time);
    adapter.operators.add(ADMIN);
    const model = new FakeModel(async () => ({
      kind: "tools",
      calls: [{ tool, arguments: { targetPlayerUuid: TARGET } }],
    }));
    const core = makeCore(time, adapter, model, new FakeAudit(), ["get_player_location"]);

    const outcome = await core.handleChat("conn-main", chat(time, {
      requesterUuid: ADMIN,
      sessionId: uuid(tool === "teleport_staff" ? 206 : 207),
      requestId: uuid(tool === "teleport_staff" ? 308 : 309),
      messageId: uuid(tool === "teleport_staff" ? 408 : 409),
      mode: "DIRECT",
      text: "자비스 테스트",
    }));

    assert.equal(outcome.status, "RESPONDED");
    assert.equal(adapter.toolRequests.length, 0);
    assert.equal(adapter.responses.length, 1);
    assert.match(adapter.responses[0].payload.text, /사용할 수 없습니다/);
  }
});

test("state-changing Tool requires durable pre-execution audit and gets an actionId", async () => {
  {
    const time = new ManualTime();
    const adapter = new FakeAdapter(time);
    adapter.operators.add(ADMIN);
    const audit = new FakeAudit(false);
    const model = new FakeModel(async () => ({
      kind: "tools",
      calls: [{ tool: "teleport_staff", arguments: { targetPlayerUuid: TARGET } }],
    }));
    const core = makeCore(time, adapter, model, audit, ["teleport_staff"]);

    const outcome = await core.handleChat("conn-main", chat(time, {
      requesterUuid: ADMIN,
      sessionId: uuid(208),
      requestId: uuid(310),
      messageId: uuid(410),
      mode: "DIRECT",
      text: "자비스 나를 Steve한테 이동시켜",
    }));

    assert.equal(outcome.status, "RESPONDED");
    assert.equal(audit.events[0].outcome, "PRE_EXECUTION");
    assert.equal(adapter.toolRequests.length, 0);
  }

  {
    const time = new ManualTime();
    const adapter = new FakeAdapter(time);
    adapter.operators.add(ADMIN);
    const audit = new FakeAudit(true);
    const model = new FakeModel(async (_input, call) => {
      if (call === 1) {
        return {
          kind: "tools",
          calls: [{ tool: "teleport_staff", arguments: { targetPlayerUuid: TARGET } }],
        };
      }
      return finalStep("이동 결과를 확인했습니다.");
    });
    const core = makeCore(time, adapter, model, audit, ["teleport_staff"]);

    const outcome = await core.handleChat("conn-main", chat(time, {
      requesterUuid: ADMIN,
      sessionId: uuid(209),
      requestId: uuid(311),
      messageId: uuid(411),
      mode: "DIRECT",
      text: "자비스 나를 Steve한테 이동시켜",
    }));

    assert.equal(outcome.status, "RESPONDED");
    assert.equal(adapter.toolRequests.length, 1);
    assert.match(adapter.toolRequests[0].actionId, /^[0-9a-f-]{36}$/i);
    assert.equal(adapter.toolRequests[0].requesterUuid, ADMIN);
    assert.equal(adapter.toolRequests[0].payload.tool, "teleport_staff");
    assert.equal(audit.events[0].outcome, "PRE_EXECUTION");
    assert.equal(audit.events[1].outcome, "OK");
    assert.ok(adapter.bindingChecks.length >= 4);
  }
});

test("Tool batch budget rejects before partial execution", async () => {
  const time = new ManualTime();
  const adapter = new FakeAdapter(time);
  adapter.operators.add(ADMIN);
  const calls = Array.from({ length: 9 }, () => ({
    tool: "get_player_location",
    arguments: { playerUuid: TARGET },
  }));
  const model = new FakeModel(async () => ({ kind: "tools", calls }));
  const core = makeCore(time, adapter, model, new FakeAudit(), ["get_player_location"]);

  const outcome = await core.handleChat("conn-main", chat(time, {
    requesterUuid: ADMIN,
    sessionId: uuid(210),
    requestId: uuid(312),
    messageId: uuid(412),
    mode: "DIRECT",
    text: "자비스 위치 여러 번 확인해",
  }));

  assert.equal(outcome.status, "RESPONDED");
  assert.equal(adapter.toolRequests.length, 0);
  assert.match(adapter.responses[0].payload.text, /요청 한도/);
});

test("mismatched Tool result binding is rejected and never reaches a second model round", async () => {
  const time = new ManualTime();
  const adapter = new FakeAdapter(time);
  adapter.operators.add(ADMIN);
  adapter.onTool = async (request) => ({
    ...toolResult(request, time.nowMs()),
    requesterUuid: OTHER,
  });
  const model = new FakeModel(async (_input, call) => {
    if (call === 1) {
      return {
        kind: "tools",
        calls: [{ tool: "get_player_location", arguments: { playerUuid: TARGET } }],
      };
    }
    return finalStep("must not run");
  });
  const core = makeCore(time, adapter, model, new FakeAudit(), ["get_player_location"]);

  const outcome = await core.handleChat("conn-main", chat(time, {
    requesterUuid: ADMIN,
    sessionId: uuid(211),
    requestId: uuid(313),
    messageId: uuid(413),
    mode: "DIRECT",
    text: "자비스 Steve 어디 있어?",
  }));

  assert.deepEqual(outcome, { status: "REJECTED", code: "UNAUTHORIZED" });
  assert.equal(model.inputs.length, 1);
  assert.equal(adapter.responses.length, 0);
});

test("reconnect invalidates old sessions and prevents late response delivery", async () => {
  const time = new ManualTime();
  const oldAdapter = new FakeAdapter(time);
  oldAdapter.operators.add(ADMIN);
  const deferred = new Deferred();
  const model = new FakeModel(async () => await deferred.promise);
  const audit = new FakeAudit();
  const core = makeCore(time, oldAdapter, model, audit, ["get_player_location"]);
  const sessionId = uuid(212);

  const pending = core.handleChat("conn-main", chat(time, {
    requesterUuid: ADMIN,
    sessionId,
    requestId: uuid(314),
    messageId: uuid(414),
    mode: "DIRECT",
    text: "자비스 오래 걸리는 질문",
  }));
  await waitUntil(() => model.inputs.length === 1);

  const newAdapter = new FakeAdapter(time);
  newAdapter.operators.add(ADMIN);
  core.registerConnection({
    connectionId: "conn-main-2",
    serverId: "main",
    adapter: newAdapter,
    capabilities: capabilitySnapshot(["get_player_location"]),
  });

  deferred.resolve(finalStep("늦은 응답"));
  const oldOutcome = await pending;
  assert.deepEqual(oldOutcome, { status: "REJECTED", code: "CANCELLED" });
  assert.equal(oldAdapter.responses.length, 0);
  assert.equal(newAdapter.responses.length, 0);

  const followUp = await core.handleChat("conn-main-2", chat(time, {
    requesterUuid: ADMIN,
    sessionId,
    requestId: uuid(315),
    messageId: uuid(415),
    mode: "FOLLOW_UP",
    text: "아까 질문 이어서",
  }));
  assert.deepEqual(followUp, { status: "REJECTED", code: "CANCELLED" });
});

test("scheduler enforces 4 active/server, 16 global queued and 2 queued/session", async () => {
  const scheduler = new RequestScheduler();
  const blocker = new Deferred();

  const active = [];
  for (let i = 0; i < 4; i += 1) {
    active.push(scheduler.submit("main", "active-" + i, async () => await blocker.promise));
  }
  await tick();

  const queued = [];
  for (let i = 0; i < 16; i += 1) {
    queued.push(scheduler.submit("main", "queued-" + i, async () => "ok"));
  }
  const snapshot = scheduler.snapshot();
  assert.equal(snapshot.activeByServer.main, 4);
  assert.equal(snapshot.queuedTotal, 16);

  await assert.rejects(
    scheduler.submit("main", "overflow", async () => "never"),
    (error) => error?.code === "BUSY",
  );

  const perSession = new RequestScheduler();
  const sessionBlocker = new Deferred();
  const p1 = perSession.submit("s", "same", async () => await sessionBlocker.promise);
  await tick();
  const p2 = perSession.submit("s", "same", async () => 2);
  const p3 = perSession.submit("s", "same", async () => 3);
  await assert.rejects(
    perSession.submit("s", "same", async () => 4),
    (error) => error?.code === "BUSY",
  );
  assert.equal(perSession.snapshot().queuedTotal, 2);

  sessionBlocker.resolve(1);
  assert.equal(await p1, 1);
  assert.equal(await p2, 2);
  assert.equal(await p3, 3);

  blocker.resolve("done");
  await Promise.all(active);
  await Promise.all(queued);
});

function makeCore(time, adapter, model, audit, tools) {
  const core = new BrainCore({ model, audit, time });
  core.registerConnection({
    connectionId: "conn-main",
    serverId: "main",
    adapter,
    capabilities: capabilitySnapshot(tools),
  });
  return core;
}

function capabilitySnapshot(tools) {
  const required = {
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
  const names = [...new Set(tools.map((tool) => required[tool]))];
  return {
    capabilities: names.map((name) => ({
      name,
      source: "FakeAdapter",
      version: "1.0",
    })),
    tools,
    limits: {
      maxMessageBytes: 65_536,
      maxToolCallsPerRequest: 8,
      maxModelRoundTripsPerRequest: 4,
    },
  };
}

function chat(time, options) {
  return {
    protocolVersion: "1.0",
    type: "chat.message",
    messageId: options.messageId,
    requestId: options.requestId,
    serverId: "main",
    sessionId: options.sessionId,
    requesterUuid: options.requesterUuid,
    sentAt: new Date(time.nowMs()).toISOString(),
    deadlineAt: new Date(time.nowMs() + 30_000).toISOString(),
    payload: {
      requesterName: options.requesterUuid === OTHER ? "OtherAdmin" : "Admin",
      text: options.text,
      mode: options.mode,
    },
  };
}

function toolResult(request, now) {
  return {
    protocolVersion: "1.0",
    type: "tool.result",
    messageId: uuid(900),
    requestId: request.requestId,
    serverId: request.serverId,
    sessionId: request.sessionId,
    requesterUuid: request.requesterUuid,
    sentAt: new Date(now).toISOString(),
    deadlineAt: request.deadlineAt,
    payload: {
      tool: request.payload.tool,
      result: {
        status: "OK",
        data: { ok: true },
        error: null,
        observedAt: new Date(now).toISOString(),
        source: "FakeAdapter",
        truncated: false,
      },
    },
    toolCallId: request.toolCallId,
    actionId: request.actionId,
  };
}

function finalStep(text) {
  return { kind: "final", text, sessionState: "CONTINUE" };
}

function uuid(value) {
  return "00000000-0000-4000-8000-" + String(value).padStart(12, "0");
}

async function tick() {
  await Promise.resolve();
  await Promise.resolve();
}

async function waitUntil(predicate) {
  for (let i = 0; i < 100; i += 1) {
    if (predicate()) {
      return;
    }
    await tick();
  }
  throw new Error("condition was not reached");
}
