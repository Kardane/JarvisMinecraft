import { timingSafeEqual, randomUUID } from "node:crypto";
import { WebSocketServer } from "ws";

import { BrainCore } from "../../../brain/dist/core/index.js";

export class AcceptanceGateway {
  constructor({
    port = 8181,
    secret = "correct-horse-battery-staple",
  } = {}) {
    this.port = port;
    this.secret = secret;
    this.server = null;
    this.connections = new Map();
    this.records = [];
    this.audit = new MemoryAudit(this.records);
    this.model = new DeterministicModel(this.records);
    this.core = new BrainCore({
      model: this.model,
      audit: this.audit,
    });
  }

  async start() {
    if (this.server !== null) return;
    await new Promise((resolve, reject) => {
      const server = new WebSocketServer({
        port: this.port,
        host: "127.0.0.1",
        maxPayload: 65_536,
        perMessageDeflate: false,
        verifyClient: ({ req }, done) => {
          const raw = req.headers["x-jarvis-secret"];
          const supplied = Array.isArray(raw) ? raw[0] : raw;
          done(safeSecretEquals(this.secret, supplied ?? ""));
        },
      });
      const onError = (error) => {
        server.off("listening", onListening);
        reject(error);
      };
      const onListening = () => {
        server.off("error", onError);
        this.server = server;
        resolve();
      };
      server.once("error", onError);
      server.once("listening", onListening);
      server.on("connection", (socket) => this.#accept(socket));
    });
    this.#record("gateway.started", { port: this.port });
  }

  async stop() {
    const server = this.server;
    if (server === null) return;

    for (const state of [...this.connections.values()]) {
      state.adapter.failPending(new Error("gateway shutdown"));
      try {
        state.socket.close(1001, "gateway shutdown");
      } catch {}
      this.core.unregisterConnection(state.connectionId);
    }
    this.connections.clear();

    await new Promise((resolve) => server.close(() => resolve()));
    this.server = null;
    this.#record("gateway.stopped", {});
  }

  async restart() {
    await this.stop();
    await sleep(250);
    await this.start();
  }

  async waitForActive(serverId, timeoutMs = 20_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const state = this.connections.get(serverId);
      if (state?.active === true) return state;
      await sleep(100);
    }
    throw new Error("Timed out waiting for Adapter capabilities: " + serverId);
  }

  snapshot() {
    return {
      generatedAt: new Date().toISOString(),
      records: structuredClone(this.records),
      auditEvents: structuredClone(this.audit.events),
      modelCalls: this.model.calls,
      activeServers: [...this.connections.entries()]
        .filter(([, value]) => value.active)
        .map(([serverId]) => serverId),
    };
  }

  chatMessages(serverId) {
    return this.records.filter(
      (record) =>
        record.kind === "inbound.chat.message" &&
        record.serverId === serverId,
    );
  }

  #accept(socket) {
    const state = {
      socket,
      connectionId: randomUUID(),
      serverId: null,
      active: false,
      adapter: null,
    };

    socket.on("message", (raw, binary) => {
      if (binary) {
        socket.close(1003, "text only");
        return;
      }
      const text = raw.toString("utf8");
      if (Buffer.byteLength(text, "utf8") > 65_536) {
        socket.close(1009, "message too large");
        return;
      }

      let message;
      try {
        message = JSON.parse(text);
      } catch {
        socket.close(1007, "invalid json");
        return;
      }
      void this.#onMessage(state, message);
    });

    socket.on("close", () => {
      if (state.serverId !== null) {
        const current = this.connections.get(state.serverId);
        if (current === state) {
          this.connections.delete(state.serverId);
        }
      }
      state.adapter?.failPending(new Error("adapter disconnected"));
      this.core.unregisterConnection(state.connectionId);
      this.#record("adapter.disconnected", {
        serverId: state.serverId,
        connectionId: state.connectionId,
      });
    });

    socket.on("error", (error) => {
      this.#record("adapter.socket_error", {
        serverId: state.serverId,
        message: safeError(error),
      });
    });
  }

  async #onMessage(state, message) {
    this.#record("inbound." + String(message.type), {
      serverId: message.serverId ?? state.serverId,
      requesterUuid: message.requesterUuid ?? null,
      requestId: message.requestId ?? null,
      sessionId: message.sessionId ?? null,
      payload: safePayload(message),
    });

    if (state.serverId === null) {
      if (
        message.protocolVersion !== "1.0" ||
        message.type !== "hello" ||
        message.payload?.side !== "adapter" ||
        typeof message.serverId !== "string"
      ) {
        state.socket.close(1008, "expected adapter hello");
        return;
      }

      state.serverId = message.serverId;
      state.adapter = new RemoteAdapter(this, state);
      const old = this.connections.get(state.serverId);
      if (old !== undefined) {
        try {
          old.socket.close(1000, "replaced");
        } catch {}
      }
      this.connections.set(state.serverId, state);

      await state.adapter.send({
        protocolVersion: "1.0",
        type: "hello",
        messageId: randomUUID(),
        requestId: null,
        serverId: state.serverId,
        sessionId: null,
        requesterUuid: null,
        sentAt: new Date().toISOString(),
        deadlineAt: null,
        payload: {
          side: "brain",
          brainInstanceId: randomUUID(),
          brainVersion: "t10-acceptance",
          accepted: true,
        },
      });
      return;
    }

    if (message.serverId !== state.serverId) {
      state.socket.close(1008, "serverId mismatch");
      return;
    }

    if (!state.active) {
      if (message.type !== "capabilities") {
        state.socket.close(1008, "expected capabilities");
        return;
      }
      const snapshot = capabilitySnapshot(message.payload);
      this.core.registerConnection({
        connectionId: state.connectionId,
        serverId: state.serverId,
        adapter: state.adapter,
        capabilities: snapshot,
      });
      state.active = true;
      this.#record("adapter.active", {
        serverId: state.serverId,
        tools: snapshot.tools,
      });
      return;
    }

    switch (message.type) {
      case "chat.message": {
        state.adapter.markActor(message.requesterUuid);
        void this.core
          .handleChat(state.connectionId, message)
          .then((outcome) => {
            this.#record("core.outcome", {
              serverId: state.serverId,
              requesterUuid: message.requesterUuid,
              requestId: message.requestId,
              outcome,
            });
          })
          .catch((error) => {
            this.#record("core.failure", {
              serverId: state.serverId,
              requestId: message.requestId,
              message: safeError(error),
            });
          });
        break;
      }
      case "tool.result":
        state.adapter.acceptToolResult(message);
        break;
      case "cancel":
        if (
          message.payload?.reason === "OP_REVOKED" ||
          message.payload?.reason === "CLIENT_DISCONNECTED" ||
          message.payload?.reason === "SESSION_ENDED"
        ) {
          state.adapter.invalidateActor(message.requesterUuid);
          if (message.requesterUuid) {
            this.core.invalidateActor(
              state.serverId,
              message.requesterUuid,
            );
          }
        }
        break;
      case "ping":
        await state.adapter.send({
          protocolVersion: "1.0",
          type: "pong",
          messageId: randomUUID(),
          requestId: null,
          serverId: state.serverId,
          sessionId: null,
          requesterUuid: null,
          sentAt: new Date().toISOString(),
          deadlineAt: null,
          payload: { nonce: message.payload?.nonce ?? "invalid" },
        });
        break;
      case "error":
      case "pong":
        break;
      default:
        state.socket.close(1008, "unexpected message");
    }
  }

  #record(kind, data) {
    this.records.push({
      at: new Date().toISOString(),
      kind,
      ...data,
    });
  }
}

class RemoteAdapter {
  constructor(gateway, state) {
    this.gateway = gateway;
    this.state = state;
    this.actors = new Set();
    this.pending = new Map();
  }

  markActor(uuid) {
    if (typeof uuid === "string") this.actors.add(uuid);
  }

  invalidateActor(uuid) {
    if (typeof uuid === "string") this.actors.delete(uuid);
  }

  async isCurrentOperator(binding) {
    return (
      this.state.active &&
      this.state.serverId === binding.serverId &&
      this.actors.has(binding.requesterUuid)
    );
  }

  async executeTool(request) {
    if (!this.state.active) throw new Error("adapter not active");
    const key = request.toolCallId;
    if (this.pending.has(key)) throw new Error("duplicate toolCallId");

    const deadline = Date.parse(request.deadlineAt);
    const timeoutMs = Math.max(1, deadline - Date.now());
    const result = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(key);
        reject(new Error("tool result timeout"));
      }, timeoutMs);
      this.pending.set(key, {
        resolve: (message) => {
          clearTimeout(timer);
          resolve(message);
        },
        reject: (error) => {
          clearTimeout(timer);
          reject(error);
        },
      });
    });

    this.gateway.records.push({
      at: new Date().toISOString(),
      kind: "outbound.tool.request",
      serverId: request.serverId,
      requesterUuid: request.requesterUuid,
      requestId: request.requestId,
      tool: request.payload.tool,
      toolCallId: request.toolCallId,
      actionId: request.actionId,
      arguments: structuredClone(request.payload.arguments),
    });
    await this.send(request);
    return await result;
  }

  async deliverResponse(response) {
    this.gateway.records.push({
      at: new Date().toISOString(),
      kind: "outbound.chat.response",
      serverId: response.serverId,
      requesterUuid: response.requesterUuid,
      requestId: response.requestId,
      text: response.payload.text,
    });
    await this.send(response);
  }

  acceptToolResult(message) {
    const pending = this.pending.get(message.toolCallId);
    if (pending === undefined) return;
    this.pending.delete(message.toolCallId);
    pending.resolve(message);
  }

  failPending(error) {
    for (const pending of this.pending.values()) {
      pending.reject(error);
    }
    this.pending.clear();
  }

  async send(message) {
    if (this.state.socket.readyState !== 1) {
      throw new Error("adapter WebSocket is not open");
    }
    const raw = JSON.stringify(message);
    await new Promise((resolve, reject) => {
      this.state.socket.send(raw, (error) => {
        if (error) reject(error);
        else resolve();
      });
    });
  }
}

class MemoryAudit {
  constructor(records) {
    this.events = [];
    this.records = records;
  }

  async record(event) {
    this.events.push(structuredClone(event));
    this.records.push({
      at: new Date().toISOString(),
      kind: "audit",
      event: structuredClone(event),
    });
    return true;
  }
}

class DeterministicModel {
  constructor(records) {
    this.modelId = "t10-deterministic-model";
    this.records = records;
    this.calls = 0;
  }

  async next(input) {
    this.calls += 1;
    const current = input.history.filter(
      (entry) => entry.requestId === input.binding.requestId,
    );
    const user = current.find((entry) => entry.role === "user");
    const tools = current.filter((entry) => entry.role === "tool");
    const text = user?.text ?? "";

    this.records.push({
      at: new Date().toISOString(),
      kind: "model.next",
      serverId: input.binding.serverId,
      requesterUuid: input.binding.requesterUuid,
      requestId: input.binding.requestId,
      text,
      toolHistory: tools.map((entry) => entry.tool),
    });

    if (text.includes("__A06_UNREGISTERED__")) {
      return {
        kind: "tools",
        calls: [{ tool: "run_console_command", arguments: { command: "op nobody" } }],
      };
    }

    if (/보내줘|텔레포트|\bTP\b/i.test(text)) {
      const target = extractTarget(text) ?? "OtherOp";
      const player = tools.find((entry) => entry.tool === "get_player");
      const teleported = tools.find((entry) => entry.tool === "teleport_staff");
      if (teleported !== undefined) {
        return {
          kind: "final",
          text: "TELEPORT_OK",
          sessionState: "CONTINUE",
        };
      }
      if (player !== undefined) {
        const uuid = player.result?.data?.player?.uuid;
        if (typeof uuid !== "string") {
          return {
            kind: "final",
            text: "TARGET_NOT_FOUND",
            sessionState: "CONTINUE",
          };
        }
        return {
          kind: "tools",
          calls: [
            {
              tool: "teleport_staff",
              arguments: { targetPlayerUuid: uuid },
            },
          ],
        };
      }
      return {
        kind: "tools",
        calls: [{ tool: "get_player", arguments: { exactName: target } }],
      };
    }

    if (/어디|좌표|위치/.test(text)) {
      const target = extractTarget(text) ?? "OtherOp";
      const player = tools.find((entry) => entry.tool === "get_player");
      const location = tools.find((entry) => entry.tool === "get_player_location");
      if (location !== undefined) {
        const data = location.result?.data?.location;
        return {
          kind: "final",
          text:
            "LOCATION " +
            JSON.stringify({
              worldId: data?.worldId ?? null,
              x: data?.x ?? null,
              y: data?.y ?? null,
              z: data?.z ?? null,
            }),
          sessionState: "CONTINUE",
        };
      }
      if (player !== undefined) {
        const uuid = player.result?.data?.player?.uuid;
        if (typeof uuid !== "string") {
          return {
            kind: "final",
            text: "TARGET_NOT_FOUND",
            sessionState: "CONTINUE",
          };
        }
        return {
          kind: "tools",
          calls: [
            {
              tool: "get_player_location",
              arguments: { playerUuid: uuid },
            },
          ],
        };
      }
      return {
        kind: "tools",
        calls: [{ tool: "get_player", arguments: { exactName: target } }],
      };
    }

    if (/접속자|온라인.*플레이어|누가.*접속/.test(text)) {
      const listed = tools.find((entry) => entry.tool === "get_online_players");
      if (listed !== undefined) {
        return {
          kind: "final",
          text:
            "ONLINE " +
            String(listed.result?.data?.returnedCount ?? 0),
          sessionState: "CONTINUE",
        };
      }
      return {
        kind: "tools",
        calls: [
          {
            tool: "get_online_players",
            arguments: { cursor: null, limit: 100 },
          },
        ],
      };
    }

    if (/서버.*상태|TPS|MSPT|렉/.test(text)) {
      const status = tools.find((entry) => entry.tool === "get_server_status");
      if (status !== undefined) {
        const data = status.result?.data;
        return {
          kind: "final",
          text:
            "SERVER_STATUS " +
            JSON.stringify({
              tps: data?.tps ?? null,
              mspt: data?.mspt ?? null,
              onlinePlayers: data?.onlinePlayers ?? null,
            }),
          sessionState: "CONTINUE",
        };
      }
      return {
        kind: "tools",
        calls: [{ tool: "get_server_status", arguments: {} }],
      };
    }

    return {
      kind: "final",
      text: "ACK",
      sessionState: "CONTINUE",
    };
  }
}

function capabilitySnapshot(payload) {
  if (
    payload === null ||
    typeof payload !== "object" ||
    !Array.isArray(payload.capabilities) ||
    !Array.isArray(payload.tools)
  ) {
    throw new Error("Invalid capabilities payload");
  }
  return {
    capabilities: payload.capabilities.map((item) => ({
      name: String(item.name),
      source: String(item.source),
      version: item.version === null ? null : String(item.version),
    })),
    tools: payload.tools.map(String),
    limits: {
      maxMessageBytes: Number(payload.limits?.maxMessageBytes),
      maxToolCallsPerRequest: Number(payload.limits?.maxToolCallsPerRequest),
      maxModelRoundTripsPerRequest: Number(payload.limits?.maxModelRoundTripsPerRequest),
    },
  };
}

function safeSecretEquals(expected, supplied) {
  const left = Buffer.from(expected, "utf8");
  const right = Buffer.from(supplied, "utf8");
  if (left.length !== right.length) return false;
  return timingSafeEqual(left, right);
}

function safePayload(message) {
  if (message.type === "hello") {
    return {
      side: message.payload?.side ?? null,
      platform: message.payload?.platform ?? null,
      minecraftVersion: message.payload?.minecraftVersion ?? null,
    };
  }
  if (message.type === "capabilities") {
    return {
      tools: Array.isArray(message.payload?.tools)
        ? [...message.payload.tools]
        : [],
    };
  }
  if (message.type === "chat.message") {
    return {
      requesterName: message.payload?.requesterName ?? null,
      text: message.payload?.text ?? null,
      mode: message.payload?.mode ?? null,
    };
  }
  if (message.type === "tool.result") {
    return {
      tool: message.payload?.tool ?? null,
      status: message.payload?.result?.status ?? null,
      source: message.payload?.result?.source ?? null,
    };
  }
  if (message.type === "cancel") {
    return {
      reason: message.payload?.reason ?? null,
      targetRequestId: message.payload?.targetRequestId ?? null,
    };
  }
  return {};
}

function extractTarget(text) {
  const direct = text.match(/([A-Za-z0-9_]{1,16})(?:한테|에게|\s+어디|\s+위치|\s+좌표)/);
  return direct?.[1] ?? null;
}

function safeError(error) {
  return error instanceof Error ? error.message.slice(0, 200) : "unknown";
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
