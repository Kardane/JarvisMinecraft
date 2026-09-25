import test from "node:test";
import assert from "node:assert/strict";
import {
  mkdtemp,
  readFile,
  readdir,
  rm,
  stat,
  utimes,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { BrainCore } from "../../dist/core/index.js";
import {
  JsonlAuditSink,
  OpsRuntime,
  loadOpsConfig,
  publicConfigSummary,
  sanitizeAuditEvent,
} from "../../dist/ops/index.js";

const BASE = Date.parse("2026-09-24T02:00:00Z");
const ADMIN = uuid(901);
const TARGET = uuid(902);
const SESSION = uuid(903);
const REQUEST = uuid(904);

test("configuration requires real secrets, loopback bind, and fixed v0.1 policy", () => {
  assert.throws(() => loadOpsConfig({}), /OPENAI_API_KEY/);
  assert.throws(
    () =>
      loadOpsConfig({
        ...validEnv(),
        JARVIS_SHARED_SECRET: "REPLACE_WITH_RANDOM_SECRET",
      }),
    /placeholder/,
  );
  assert.throws(
    () =>
      loadOpsConfig({
        ...validEnv(),
        JARVIS_BRAIN_HOST: "0.0.0.0",
      }),
    /loopback/,
  );

  const config = loadOpsConfig(validEnv());
  assert.deepEqual(config.policy, {
    opOnly: true,
    replyRequesterOnly: true,
    warningEnabled: false,
    rollbackEnabled: false,
  });
  assert.equal(config.auditRetentionDays, 7);
  assert.equal(config.auditMaxTotalBytes, 100 * 1024 * 1024);

  const summary = publicConfigSummary(config);
  const encoded = JSON.stringify(summary);
  assert.doesNotMatch(encoded, /openai-real-secret/);
  assert.doesNotMatch(encoded, /typesafe-real-secret/);
  assert.doesNotMatch(encoded, /adapter-shared-secret/);
});

test("audit masking removes sensitive keys and known secret patterns", () => {
  const raw = event({
    argumentSummary: {
      apiKey: "sk-this-must-not-survive",
      nested: {
        authorization: "Bearer abcdefghijklmnop",
        note: "OPENAI_API_KEY=sk-secret-in-text",
      },
    },
    source: "provider Bearer qwertyuiopasdfgh",
  });
  const masked = sanitizeAuditEvent(raw);
  const encoded = JSON.stringify(masked);

  assert.doesNotMatch(encoded, /this-must-not-survive/);
  assert.doesNotMatch(encoded, /abcdefghijklmnop/);
  assert.doesNotMatch(encoded, /secret-in-text/);
  assert.doesNotMatch(encoded, /qwertyuiopasdfgh/);
  assert.match(encoded, /\[redacted\]/);
  assert.equal(masked.requesterUuid, ADMIN);
  assert.equal(masked.requestId, REQUEST);
});

test("JSONL audit writes durable masked lines to an isolated directory", async () => {
  const directory = await mkdtemp(join(tmpdir(), "jarvis-t09-audit-"));
  try {
    const sink = new JsonlAuditSink({
      directory,
      nowMs: () => BASE,
    });

    const accepted = await sink.record(
      event({
        argumentSummary: {
          targetPlayerUuid: TARGET,
          secret: "adapter-shared-secret-123456",
          note: "TYPESAFE_API_KEY=typesafe-real-secret-123456",
        },
      }),
    );
    assert.equal(accepted, true);

    const names = await readdir(directory);
    assert.equal(names.length, 1);
    assert.match(names[0], /^jarvis-audit-2026-09-24-0001\.jsonl$/);

    const data = await readFile(join(directory, names[0]), "utf8");
    const parsed = JSON.parse(data.trim());
    assert.equal(parsed.tool, "teleport_staff");
    assert.equal(parsed.outcome, "PRE_EXECUTION");
    assert.equal(parsed.argumentSummary.targetPlayerUuid, TARGET);
    assert.equal(parsed.argumentSummary.secret, "[redacted]");
    assert.doesNotMatch(data, /adapter-shared-secret/);
    assert.doesNotMatch(data, /typesafe-real-secret/);

    const health = sink.health();
    assert.equal(health.status, "HEALTHY");
    assert.equal(health.writable, true);
    assert.equal(health.fileCount, 1);
    assert.ok(health.totalBytes > 0);
    await sink.close();
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("rotation, retention and total cap prevent unbounded audit growth", async () => {
  const directory = await mkdtemp(join(tmpdir(), "jarvis-t09-rotate-"));
  try {
    const oldName = "jarvis-audit-2026-09-01-0001.jsonl";
    const oldPath = join(directory, oldName);
    await writeFile(oldPath, "x".repeat(700), "utf8");
    const oldTime = new Date("2026-09-01T00:00:00Z");
    await utimes(oldPath, oldTime, oldTime);

    let now = BASE;
    const sink = new JsonlAuditSink({
      directory,
      retentionDays: 7,
      maxFileBytes: 1_200,
      maxTotalBytes: 2_400,
      maxQueue: 16,
      nowMs: () => now,
    });

    for (let index = 0; index < 5; index += 1) {
      const accepted = await sink.record(
        event({
          requestId: uuid(920 + index),
          toolCallId: uuid(930 + index),
          argumentSummary: {
            note: "z".repeat(500),
            sequence: index,
          },
        }),
      );
      assert.equal(accepted, true);
      now += 1_000;
    }

    const names = (await readdir(directory)).sort();
    assert.equal(names.includes(oldName), false);
    assert.ok(names.length >= 1);
    assert.ok(names.every((name) => /^jarvis-audit-2026-09-24-\d{4}\.jsonl$/.test(name)));

    let total = 0;
    for (const name of names) {
      const metadata = await stat(join(directory, name));
      assert.ok(metadata.size <= 1_200);
      total += metadata.size;
    }
    assert.ok(total <= 2_400);
    assert.equal(sink.health().totalBytes, total);
    await sink.close();
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("disk failure returns false, exposes unhealthy health, and can recover", async () => {
  const fs = new MemoryFs();
  fs.failAppend = true;
  const sink = new JsonlAuditSink({
    directory: "/audit",
    maxFileBytes: 2_048,
    maxTotalBytes: 4_096,
    fileSystem: fs,
    nowMs: () => BASE,
  });

  assert.equal(await sink.record(event()), false);
  assert.equal(sink.health().status, "UNHEALTHY");
  assert.equal(sink.health().writable, false);
  assert.equal(sink.health().lastErrorCode, "AUDIT_IO_ERROR");

  fs.failAppend = false;
  assert.equal(await sink.record(event({ requestId: uuid(940) })), true);
  assert.equal(sink.health().status, "HEALTHY");
  assert.equal(sink.health().writable, true);
  assert.equal(sink.health().lastErrorCode, null);
});

test("queue saturation is explicit and close drains accepted records", async () => {
  const fs = new BlockingFs();
  const sink = new JsonlAuditSink({
    directory: "/audit",
    maxFileBytes: 4_096,
    maxTotalBytes: 8_192,
    maxQueue: 1,
    fileSystem: fs,
    nowMs: () => BASE,
  });

  const first = sink.record(event({ requestId: uuid(950) }));
  await tick();
  const second = sink.record(event({ requestId: uuid(951) }));
  const rejected = await sink.record(event({ requestId: uuid(952) }));

  assert.equal(rejected, false);
  assert.equal(sink.health().lastErrorCode, "AUDIT_QUEUE_FULL");
  assert.equal(sink.health().rejectedRecords, 1);

  const closing = sink.close();
  let closed = false;
  closing.then(() => {
    closed = true;
  });
  await tick();
  assert.equal(closed, false);

  fs.release();
  assert.equal(await first, true);
  assert.equal(await second, true);
  await closing;
  assert.equal(sink.health().closed, true);
  assert.equal(fs.appended.length, 2);
});

test("OpsRuntime health never contains provider or shared-secret values", async () => {
  const runtime = OpsRuntime.fromEnvironment(validEnv(), {
    fileSystem: new MemoryFs(),
    nowMs: () => BASE,
  });
  const health = runtime.health();
  assert.equal(health.status, "HEALTHY");
  assert.equal(health.providers.openaiConfigured, true);
  assert.equal(health.providers.typesafeConfigured, true);

  const encoded = JSON.stringify(health);
  assert.doesNotMatch(encoded, /openai-real-secret/);
  assert.doesNotMatch(encoded, /typesafe-real-secret/);
  assert.doesNotMatch(encoded, /adapter-shared-secret/);
  await runtime.close();
});

test("Brain Core refuses a state-changing Tool when durable pre-execution audit fails", async () => {
  const fs = new MemoryFs();
  fs.failAppend = true;
  const audit = new JsonlAuditSink({
    directory: "/audit",
    maxFileBytes: 4_096,
    maxTotalBytes: 8_192,
    fileSystem: fs,
    nowMs: () => BASE,
  });

  let modelCalls = 0;
  const model = {
    modelId: "gpt-6-luna",
    async next() {
      modelCalls += 1;
      return {
        kind: "tools",
        calls: [
          {
            tool: "teleport_staff",
            arguments: { targetPlayerUuid: TARGET },
          },
        ],
      };
    },
  };

  let toolExecutions = 0;
  const responses = [];
  const adapter = {
    async isRequestBindingActive() {
      return true;
    },
    async executeTool() {
      toolExecutions += 1;
      throw new Error("must not execute");
    },
    async deliverResponse(response) {
      responses.push(response);
    },
  };

  const core = new BrainCore({
    model,
    audit,
    time: { nowMs: () => BASE },
  });
  core.registerConnection({
    connectionId: "conn-main",
    serverId: "main",
    adapter,
    capabilities: {
      capabilities: [
        {
          name: "staff.self_teleport",
          source: "FakeAdapter",
          version: "1.0",
        },
      ],
      tools: ["teleport_staff"],
      limits: {
        maxMessageBytes: 65_536,
        maxToolCallsPerRequest: 8,
        maxModelRoundTripsPerRequest: 4,
      },
    },
  });

  const outcome = await core.handleChat("conn-main", {
    protocolVersion: "1.0",
    type: "chat.message",
    messageId: uuid(960),
    requestId: REQUEST,
    serverId: "main",
    sessionId: SESSION,
    requesterUuid: ADMIN,
    sentAt: new Date(BASE).toISOString(),
    deadlineAt: new Date(BASE + 30_000).toISOString(),
    payload: {
      requesterName: "Admin",
      text: "자비스 나를 Steve에게 보내줘",
      mode: "DIRECT",
    },
  });

  assert.equal(modelCalls, 1);
  assert.equal(toolExecutions, 0);
  assert.equal(outcome.status, "RESPONDED");
  assert.equal(responses.length, 1);
  assert.match(responses[0].payload.text, /내부 문제/);
  assert.equal(audit.health().status, "UNHEALTHY");
});

function validEnv() {
  return {
    OPENAI_API_KEY: "openai-real-secret-123456789",
    TYPESAFE_API_KEY: "typesafe-real-secret-123456789",
    JARVIS_SHARED_SECRET: "adapter-shared-secret-123456789",
  };
}

function event(overrides = {}) {
  return {
    timestamp: new Date(BASE).toISOString(),
    serverId: "main",
    requesterUuid: ADMIN,
    requestId: REQUEST,
    toolCallId: uuid(905),
    actionId: uuid(906),
    tool: "teleport_staff",
    risk: "LOW",
    argumentSummary: {
      targetPlayerUuid: TARGET,
    },
    outcome: "PRE_EXECUTION",
    source: "BrainPolicy",
    latencyMs: 0,
    modelId: "gpt-6-luna",
    fallbackReason: null,
    capabilities: [
      {
        name: "staff.self_teleport",
        source: "FakeAdapter",
        version: "1.0",
      },
    ],
    ...overrides,
  };
}

function uuid(value) {
  return "00000000-0000-4000-8000-" + String(value).padStart(12, "0");
}

function tick() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

class MemoryFs {
  constructor() {
    this.files = new Map();
    this.failAppend = false;
  }

  async ensureDirectory() {}

  async list() {
    return [...this.files.entries()].map(([name, file]) => ({
      name,
      size: new TextEncoder().encode(file.data).byteLength,
      mtimeMs: file.mtimeMs,
    }));
  }

  async append(path, data) {
    if (this.failAppend) {
      throw new Error("disk full");
    }
    const name = path.split("/").at(-1);
    const previous = this.files.get(name);
    this.files.set(name, {
      data: (previous?.data ?? "") + data,
      mtimeMs: BASE,
    });
  }

  async remove(path) {
    const name = path.split("/").at(-1);
    this.files.delete(name);
  }
}

class BlockingFs extends MemoryFs {
  constructor() {
    super();
    this.appended = [];
    this.blocked = true;
    this.waiters = [];
  }

  async append(path, data) {
    if (this.blocked) {
      await new Promise((resolve) => this.waiters.push(resolve));
    }
    await super.append(path, data);
    this.appended.push({ path, data });
  }

  release() {
    this.blocked = false;
    for (const resolve of this.waiters.splice(0)) {
      resolve();
    }
  }
}
