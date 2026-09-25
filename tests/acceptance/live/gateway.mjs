import { BrainCore } from "../../../brain/dist/core/index.js";
import { BrainWebSocketServer } from "../../../brain/dist/server/index.js";

export class AcceptanceGateway {
  constructor({
    port = 8181,
    secret = "correct-horse-battery-staple",
  } = {}) {
    this.port = port;
    this.secret = secret;
    this.records = [];
    this.audit = new MemoryAudit(this.records);
    this.model = new DeterministicModel(this.records);
    this.core = new BrainCore({
      model: this.model,
      audit: this.audit,
    });
    this.server = new BrainWebSocketServer({
      core: this.core,
      host: "127.0.0.1",
      port: this.port,
      path: "/ws",
      sharedSecret: this.secret,
      brainVersion: "t10-production-transport",
      observer: {
        onEvent: (event) => this.#acceptEvent(event),
      },
    });
  }

  async start() {
    await this.server.start();
  }

  async stop() {
    await this.server.stop();
  }

  async restart() {
    await this.stop();
    await sleep(250);
    await this.start();
  }

  async waitForActive(serverId, timeoutMs = 20_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (this.server.health().activeServers.includes(serverId)) {
        return;
      }
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
      activeServers: [...this.server.health().activeServers],
      productionTransport: true,
    };
  }

  chatMessages(serverId) {
    return this.records.filter(
      (record) =>
        record.kind === "inbound.chat.message" &&
        record.serverId === serverId,
    );
  }

  #acceptEvent(event) {
    const kind = normalizeEventKind(event.kind);
    const details = structuredClone(event.details ?? {});
    const record = {
      at: event.at,
      kind,
      serverId: event.serverId,
      connectionId: event.connectionId,
      requestId: event.requestId,
      ...details,
    };
    if (event.kind === "chat.message") {
      record.payload = {
        requesterUuid: details.requesterUuid ?? null,
        mode: details.mode ?? null,
      };
    }
    this.records.push(record);
  }
}

function normalizeEventKind(kind) {
  switch (kind) {
    case "chat.message":
      return "inbound.chat.message";
    case "tool.request":
      return "outbound.tool.request";
    case "tool.result":
      return "inbound.tool.result";
    case "chat.response":
      return "outbound.chat.response";
    default:
      return kind;
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


function extractTarget(text) {
  const direct = text.match(/([A-Za-z0-9_]{1,16})(?:한테|에게|\s+어디|\s+위치|\s+좌표)/);
  return direct?.[1] ?? null;
}


function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
