import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:net";

import WebSocket from "ws";

import { BrainCore } from "../../dist/core/index.js";
import { BrainWebSocketServer } from "../../dist/server/index.js";

const SECRET = "correct-horse-battery-staple";
const ADMIN = uuid(501);
const TARGET = uuid(502);

class FakeAudit {
  constructor() {
    this.events = [];
  }
  async record(event) {
    this.events.push(event);
    return true;
  }
}

class FakeModel {
  constructor(handler) {
    this.modelId = "server-test-model";
    this.handler = handler;
    this.calls = 0;
  }
  async next(input) {
    this.calls += 1;
    return await this.handler(input, this.calls);
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

test("rejects WebSocket upgrade without the configured shared secret", async () => {
  const fixture = await createFixture(new FakeModel(async () => finalStep("ok")));
  try {
    await assert.rejects(
      openSocket(fixture.url, "wrong-secret-value-123456"),
      /401|Unexpected server response|WebSocket/i,
    );
    assert.deepEqual(fixture.server.health().activeServers, []);
  } finally {
    await fixture.close();
  }
});

test("activates only after hello + capabilities and returns requester-bound response", async () => {
  const fixture = await createFixture(new FakeModel(async () => finalStep("pong")));
  const client = await openSocket(fixture.url, SECRET);
  try {
    const wire = createWire(client);
    await activate(wire, "server-a");

    assert.deepEqual(fixture.server.health().activeServers, ["server-a"]);
    const requestId = uuid(601);
    const sessionId = uuid(602);
    wire.send(chat({
      serverId: "server-a",
      requestId,
      sessionId,
      text: "자비스 안녕",
    }));

    const response = await wire.next("chat.response");
    assert.equal(response.requestId, requestId);
    assert.equal(response.sessionId, sessionId);
    assert.equal(response.requesterUuid, ADMIN);
    assert.equal(response.payload.text, "pong");
    assert.equal(response.payload.final, true);
    assert.equal(response.payload.sessionState, "CONTINUE");
  } finally {
    client.close();
    await fixture.close();
  }
});

test("bridges Tool request/result through the real WebSocket transport", async () => {
  const model = new FakeModel(async (input, round) => {
    if (round === 1) {
      return {
        kind: "tools",
        calls: [{ tool: "get_server_status", arguments: {} }],
      };
    }
    const tool = input.history.find((entry) => entry.role === "tool");
    assert.equal(tool?.tool, "get_server_status");
    assert.equal(tool?.result?.data?.tps?.value, 19.9);
    return finalStep("SERVER_OK");
  });
  const fixture = await createFixture(model);
  const client = await openSocket(fixture.url, SECRET);
  try {
    const wire = createWire(client);
    await activate(wire, "server-tools");

    const requestId = uuid(603);
    const sessionId = uuid(604);
    wire.send(chat({
      serverId: "server-tools",
      requestId,
      sessionId,
      text: "자비스 서버 상태",
    }));

    const toolRequest = await wire.next("tool.request");
    assert.equal(toolRequest.requestId, requestId);
    assert.equal(toolRequest.payload.tool, "get_server_status");
    assert.equal(toolRequest.actionId, null);

    wire.send({
      protocolVersion: "1.0",
      type: "tool.result",
      messageId: uuid(605),
      requestId,
      serverId: "server-tools",
      sessionId,
      requesterUuid: ADMIN,
      sentAt: nowIso(),
      deadlineAt: toolRequest.deadlineAt,
      payload: {
        tool: "get_server_status",
        result: {
          status: "OK",
          data: {
            tps: {
              value: 19.9,
              unit: "tps",
              observedAt: nowIso(),
              source: "TestAdapter",
            },
          },
          error: null,
          observedAt: nowIso(),
          source: "TestAdapter",
          truncated: false,
        },
      },
      toolCallId: toolRequest.toolCallId,
      actionId: null,
    });

    const response = await wire.next("chat.response");
    assert.equal(response.payload.text, "SERVER_OK");
    assert.equal(model.calls, 2);
  } finally {
    client.close();
    await fixture.close();
  }
});

test("same server reconnect replaces old socket and leaves only the new connection active", async () => {
  const fixture = await createFixture(new FakeModel(async () => finalStep("ok")));
  const first = await openSocket(fixture.url, SECRET);
  const second = await openSocket(fixture.url, SECRET);
  try {
    const firstWire = createWire(first);
    await activate(firstWire, "same-server");
    assert.deepEqual(fixture.server.health().activeServers, ["same-server"]);

    const firstClosed = waitForClose(first);
    const secondWire = createWire(second);
    await activate(secondWire, "same-server");
    const close = await firstClosed;

    assert.equal(close.code, 1000);
    assert.deepEqual(fixture.server.health().activeServers, ["same-server"]);
    assert.equal(fixture.server.health().connectionCount, 1);
  } finally {
    try { first.close(); } catch {}
    try { second.close(); } catch {}
    await fixture.close();
  }
});

test("OP_REVOKED cancel during model wait prevents a later state-changing Tool request", async () => {
  const gate = new Deferred();
  const model = new FakeModel(async () => await gate.promise);
  const fixture = await createFixture(model);
  const client = await openSocket(fixture.url, SECRET);
  try {
    const wire = createWire(client);
    await activate(wire, "server-cancel");

    const requestId = uuid(606);
    const sessionId = uuid(607);
    wire.send(chat({
      serverId: "server-cancel",
      requestId,
      sessionId,
      text: "자비스 나를 보내줘",
    }));
    await waitUntil(() => model.calls === 1);

    wire.send({
      protocolVersion: "1.0",
      type: "cancel",
      messageId: uuid(608),
      requestId,
      serverId: "server-cancel",
      sessionId,
      requesterUuid: ADMIN,
      sentAt: nowIso(),
      deadlineAt: new Date(Date.now() + 5_000).toISOString(),
      payload: {
        targetRequestId: requestId,
        reason: "OP_REVOKED",
      },
    });

    gate.resolve({
      kind: "tools",
      calls: [{
        tool: "teleport_staff",
        arguments: { targetPlayerUuid: TARGET },
      }],
    });

    await sleep(300);
    assert.equal(
      wire.received.some((message) => message.type === "tool.request"),
      false,
    );
    assert.equal(
      wire.received.some((message) => message.type === "chat.response"),
      false,
    );
  } finally {
    client.close();
    await fixture.close();
  }
});

test("serverId mismatch after authentication closes the connection", async () => {
  const fixture = await createFixture(new FakeModel(async () => finalStep("ok")));
  const client = await openSocket(fixture.url, SECRET);
  try {
    const wire = createWire(client);
    await activate(wire, "bound-server");

    const closed = waitForClose(client);
    wire.send(chat({
      serverId: "other-server",
      requestId: uuid(609),
      sessionId: uuid(610),
      text: "자비스 안녕",
    }));
    const result = await closed;
    assert.equal(result.code, 1008);
  } finally {
    try { client.close(); } catch {}
    await fixture.close();
  }
});

async function createFixture(model) {
  const port = await freePort();
  const core = new BrainCore({
    model,
    audit: new FakeAudit(),
  });
  const server = new BrainWebSocketServer({
    core,
    host: "127.0.0.1",
    port,
    sharedSecret: SECRET,
    handshakeTimeoutMs: 2_000,
    brainVersion: "server-test",
  });
  await server.start();
  return {
    server,
    url: "ws://127.0.0.1:" + port + "/ws",
    async close() {
      await server.stop();
    },
  };
}

async function activate(wire, serverId) {
  wire.send({
    protocolVersion: "1.0",
    type: "hello",
    messageId: uuid(nextId()),
    requestId: null,
    serverId,
    sessionId: null,
    requesterUuid: null,
    sentAt: nowIso(),
    deadlineAt: null,
    payload: {
      side: "adapter",
      adapterInstanceId: uuid(nextId()),
      platform: "paper",
      minecraftVersion: "1.21.8",
      adapterVersion: "test",
      platformVersion: "test",
    },
  });

  const hello = await wire.next("hello");
  assert.equal(hello.payload.side, "brain");
  assert.equal(hello.payload.accepted, true);
  assert.equal(hello.serverId, serverId);

  wire.send({
    protocolVersion: "1.0",
    type: "capabilities",
    messageId: uuid(nextId()),
    requestId: null,
    serverId,
    sessionId: null,
    requesterUuid: null,
    sentAt: nowIso(),
    deadlineAt: null,
    payload: {
      capabilities: [
        { name: "server.status", source: "TestAdapter", version: "1" },
        { name: "staff.self_teleport", source: "TestAdapter", version: "1" },
      ],
      tools: ["get_server_status", "teleport_staff"],
      limits: {
        maxMessageBytes: 65_536,
        maxToolCallsPerRequest: 8,
        maxModelRoundTripsPerRequest: 4,
      },
    },
  });
  await waitUntil(() => wire.serverHealth?.().activeServers?.includes(serverId) ?? true, 10);
  await sleep(20);
}

function chat({ serverId, requestId, sessionId, text }) {
  return {
    protocolVersion: "1.0",
    type: "chat.message",
    messageId: uuid(nextId()),
    requestId,
    serverId,
    sessionId,
    requesterUuid: ADMIN,
    sentAt: nowIso(),
    deadlineAt: new Date(Date.now() + 10_000).toISOString(),
    payload: {
      requesterName: "Admin",
      text,
      mode: "DIRECT",
    },
  };
}

function createWire(socket) {
  const received = [];
  const waiters = [];
  socket.on("message", (raw, isBinary) => {
    if (isBinary) return;
    const parsed = JSON.parse(raw.toString("utf8"));
    received.push(parsed);
    for (const waiter of [...waiters]) {
      if (waiter.type === parsed.type) {
        waiters.splice(waiters.indexOf(waiter), 1);
        clearTimeout(waiter.timer);
        waiter.resolve(parsed);
      }
    }
  });

  return {
    received,
    send(message) {
      socket.send(JSON.stringify(message));
    },
    next(type, timeoutMs = 5_000) {
      const existing = received.find((message) => message.type === type && !message.__consumed);
      if (existing !== undefined) {
        Object.defineProperty(existing, "__consumed", {
          value: true,
          enumerable: false,
        });
        return Promise.resolve(existing);
      }
      return new Promise((resolve, reject) => {
        const waiter = {
          type,
          resolve: (message) => {
            Object.defineProperty(message, "__consumed", {
              value: true,
              enumerable: false,
            });
            resolve(message);
          },
          reject,
          timer: null,
        };
        waiter.timer = setTimeout(() => {
          const index = waiters.indexOf(waiter);
          if (index >= 0) waiters.splice(index, 1);
          reject(new Error("Timed out waiting for " + type));
        }, timeoutMs);
        waiters.push(waiter);
      });
    },
  };
}

async function openSocket(url, secret) {
  return await new Promise((resolve, reject) => {
    const socket = new WebSocket(url, {
      headers: { "X-Jarvis-Secret": secret },
    });
    const onOpen = () => {
      cleanup();
      resolve(socket);
    };
    const onError = (error) => {
      cleanup();
      reject(error);
    };
    const onUnexpected = (_request, response) => {
      cleanup();
      reject(new Error("Unexpected server response: " + response.statusCode));
    };
    const cleanup = () => {
      socket.off("open", onOpen);
      socket.off("error", onError);
      socket.off("unexpected-response", onUnexpected);
    };
    socket.once("open", onOpen);
    socket.once("error", onError);
    socket.once("unexpected-response", onUnexpected);
  });
}

function waitForClose(socket, timeoutMs = 5_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Timed out waiting for close")), timeoutMs);
    socket.once("close", (code, raw) => {
      clearTimeout(timer);
      resolve({ code, reason: raw.toString("utf8") });
    });
  });
}

async function freePort() {
  return await new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address !== null ? address.port : null;
      server.close((error) => {
        if (error) reject(error);
        else if (port === null) reject(new Error("No free port"));
        else resolve(port);
      });
    });
  });
}

let sequence = 700;
function nextId() {
  sequence += 1;
  return sequence;
}

function uuid(value) {
  return "00000000-0000-4000-8000-" + String(value).padStart(12, "0");
}

function nowIso() {
  return new Date().toISOString();
}

function finalStep(text) {
  return { kind: "final", text, sessionState: "CONTINUE" };
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitUntil(predicate, maxTicks = 100) {
  for (let index = 0; index < maxTicks; index += 1) {
    if (predicate()) return;
    await sleep(10);
  }
  throw new Error("condition was not reached");
}
