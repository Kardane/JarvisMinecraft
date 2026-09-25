import { randomUUID, timingSafeEqual } from "node:crypto";
import {
  WebSocket,
  WebSocketServer,
  type RawData,
  type VerifyClientInfo,
} from "ws";

import type { BrainCore } from "../core/brain-core.js";
import { PROTOCOL_LIMITS, PROTOCOL_VERSION } from "../generated/contract-constants.js";
import { assertProtocolMessage } from "../protocol/schema-validator.js";
import type {
  CapabilitySnapshot,
  ChatMessageEnvelope,
  JsonObject,
  JsonValue,
  ToolResultEnvelope,
} from "../core/types.js";
import { RemoteAdapter } from "./remote-adapter.js";
import type {
  BrainServerEvent,
  BrainServerHealth,
  BrainServerObserver,
  BrainWebSocketServerOptions,
} from "./types.js";

interface ConnectionState {
  readonly socket: WebSocket;
  readonly connectionId: string;
  readonly handshakeTimer: ReturnType<typeof globalThis.setTimeout>;
  serverId: string | null;
  adapter: RemoteAdapter | null;
  active: boolean;
  closed: boolean;
}

const NOOP_OBSERVER: BrainServerObserver = {
  onEvent(): void {},
};

export class BrainWebSocketServer {
  readonly #core: BrainCore;
  readonly #host: string;
  readonly #port: number;
  readonly #path: string;
  readonly #secret: string;
  readonly #brainVersion: string;
  readonly #handshakeTimeoutMs: number;
  readonly #observer: BrainServerObserver;
  readonly #nowMs: () => number;
  readonly #brainInstanceId = randomUUID();
  readonly #connections = new Set<ConnectionState>();
  readonly #byServer = new Map<string, ConnectionState>();

  #server: WebSocketServer | null = null;

  constructor(options: BrainWebSocketServerOptions) {
    this.#core = options.core;
    this.#host = options.host;
    this.#port = options.port;
    this.#path = options.path ?? "/ws";
    this.#secret = validateSecret(options.sharedSecret);
    this.#brainVersion = options.brainVersion ?? "0.1.0-dev";
    this.#handshakeTimeoutMs = options.handshakeTimeoutMs ?? 5_000;
    this.#observer = options.observer ?? NOOP_OBSERVER;
    this.#nowMs = options.nowMs ?? (() => Date.now());

    if (!isLoopbackHost(this.#host)) {
      throw new Error("Brain WebSocket host must be loopback-only.");
    }
    if (!Number.isInteger(this.#port) || this.#port < 1 || this.#port > 65_535) {
      throw new Error("Brain WebSocket port is invalid.");
    }
    if (!this.#path.startsWith("/") || this.#path.includes("?") || this.#path.includes("#")) {
      throw new Error("Brain WebSocket path is invalid.");
    }
    if (
      !Number.isFinite(this.#handshakeTimeoutMs) ||
      this.#handshakeTimeoutMs < 100 ||
      this.#handshakeTimeoutMs > 30_000
    ) {
      throw new Error("Brain handshake timeout is invalid.");
    }
  }

  async start(): Promise<void> {
    if (this.#server !== null) {
      return;
    }

    await new Promise<void>((resolve, reject) => {
      let server: WebSocketServer;
      try {
        server = new WebSocketServer({
          host: this.#host,
          port: this.#port,
          path: this.#path,
          maxPayload: PROTOCOL_LIMITS.maxMessageBytes,
          perMessageDeflate: false,
          verifyClient: (info, done) => this.#authenticate(info, done),
        });
      } catch (error) {
        reject(asError(error));
        return;
      }

      const onListening = () => {
        server.off("error", onError);
        this.#server = server;
        server.on("connection", (socket) => this.#accept(socket));
        this.#emit("server.listening", null, null, null, {
          host: this.#host,
          port: this.#port,
          path: this.#path,
        });
        resolve();
      };
      const onError = (error: Error) => {
        server.off("listening", onListening);
        reject(error);
      };

      server.once("error", onError);
      server.once("listening", onListening);
    });
  }

  async stop(): Promise<void> {
    const server = this.#server;
    if (server === null) {
      return;
    }
    this.#server = null;

    for (const connection of [...this.#connections]) {
      this.#closeConnection(connection, 1001, "brain shutdown");
    }

    await new Promise<void>((resolve, reject) => {
      server.close((error) => {
        if (error != null) {
          reject(error);
        } else {
          resolve();
        }
      });
    });
    this.#emit("server.stopped", null, null, null, {});
  }

  health(): BrainServerHealth {
    return {
      status: this.#server === null ? "STOPPED" : "LISTENING",
      host: this.#host,
      port: this.#port,
      path: this.#path,
      activeServers: [...this.#byServer.entries()]
        .filter(([, connection]) => connection.active && !connection.closed)
        .map(([serverId]) => serverId)
        .sort(),
      connectionCount: [...this.#connections].filter(
        (connection) => !connection.closed,
      ).length,
    };
  }

  #authenticate(
    info: VerifyClientInfo,
    done: (
      accepted: boolean,
      code?: number,
      message?: string,
      headers?: Readonly<Record<string, string>>,
    ) => void,
  ): void {
    const raw = info.req.headers["x-jarvis-secret"];
    const supplied = Array.isArray(raw) ? raw[0] : raw;
    if (typeof supplied !== "string" || !safeSecretEquals(this.#secret, supplied)) {
      this.#emit("auth.rejected", null, null, null, {});
      done(false, 401, "Unauthorized");
      return;
    }
    done(true);
  }

  #accept(socket: WebSocket): void {
    const connectionId = randomUUID();
    const state: ConnectionState = {
      socket,
      connectionId,
      serverId: null,
      adapter: null,
      active: false,
      closed: false,
      handshakeTimer: globalThis.setTimeout(() => {
        this.#closeConnection(state, 1008, "handshake timeout");
      }, this.#handshakeTimeoutMs),
    };
    this.#connections.add(state);
    this.#emit("connection.accepted", null, connectionId, null, {});

    socket.on("message", (raw, isBinary) => {
      if (isBinary) {
        this.#closeConnection(state, 1003, "text messages only");
        return;
      }
      const text = rawToText(raw);
      if (new TextEncoder().encode(text).byteLength > PROTOCOL_LIMITS.maxMessageBytes) {
        this.#closeConnection(state, 1009, "message too large");
        return;
      }

      let parsed: unknown;
      try {
        parsed = JSON.parse(text);
      } catch {
        this.#closeConnection(state, 1007, "invalid json");
        return;
      }

      void this.#onMessage(state, parsed).catch((error) => {
        this.#emit(
          "message.failure",
          state.serverId,
          state.connectionId,
          requestIdOf(parsed),
          { message: safeError(error) },
        );
        this.#closeConnection(state, 1008, "protocol violation");
      });
    });

    socket.on("close", () => {
      this.#detach(state, "remote close");
    });

    socket.on("error", (error) => {
      this.#emit(
        "socket.error",
        state.serverId,
        state.connectionId,
        null,
        { message: safeError(error) },
      );
      this.#detach(state, "socket error");
    });
  }

  async #onMessage(state: ConnectionState, raw: unknown): Promise<void> {
    if (state.closed) {
      return;
    }
    assertProtocolMessage(raw);
    const message = raw;

    if (state.serverId === null) {
      await this.#handleHello(state, message);
      return;
    }

    const serverId = message.serverId as string;
    if (serverId !== state.serverId) {
      throw new Error("serverId does not match authenticated connection.");
    }

    if (!state.active) {
      await this.#handleCapabilities(state, message);
      return;
    }

    const type = message.type;
    switch (type) {
      case "chat.message":
        await this.#handleChat(state, parseChatMessage(message));
        break;
      case "tool.result":
        this.#handleToolResult(state, parseToolResult(message));
        break;
      case "cancel":
        this.#handleCancel(state, message);
        break;
      case "ping":
        await this.#handlePing(state, message);
        break;
      case "pong":
      case "error":
        this.#emit("inbound." + type, state.serverId, state.connectionId, requestIdOf(message), {});
        break;
      default:
        throw new Error("Unexpected Adapter message type after activation.");
    }
  }

  async #handleHello(
    state: ConnectionState,
    message: JsonObject,
  ): Promise<void> {
    if (message.type !== "hello") {
      throw new Error("Adapter hello must be the first protocol message.");
    }
    const serverId = message.serverId as string;
    const payload = message.payload as JsonObject;
    if (payload.side !== "adapter") {
      throw new Error("Hello side must be adapter.");
    }
    const platform = payload.platform as string;
    if (payload.minecraftVersion !== "1.21.8") {
      throw new Error("Adapter Minecraft version is unsupported.");
    }

    state.serverId = serverId;
    state.adapter = new RemoteAdapter(
      state.socket,
      serverId,
      (kind, requestId, details) =>
        this.#emit(kind, serverId, state.connectionId, requestId, details),
    );

    await state.adapter.send({
      protocolVersion: PROTOCOL_VERSION,
      type: "hello",
      messageId: randomUUID(),
      requestId: null,
      serverId,
      sessionId: null,
      requesterUuid: null,
      sentAt: new Date(this.#nowMs()).toISOString(),
      deadlineAt: null,
      payload: {
        side: "brain",
        brainInstanceId: this.#brainInstanceId,
        brainVersion: this.#brainVersion,
        accepted: true,
      },
    });
    this.#emit("hello.accepted", serverId, state.connectionId, null, {
      platform,
    });
  }

  async #handleCapabilities(
    state: ConnectionState,
    message: JsonObject,
  ): Promise<void> {
    if (message.type !== "capabilities") {
      throw new Error("Capabilities must follow the Brain hello.");
    }

    const snapshot = parseCapabilities(message.payload as JsonObject);
    const serverId = state.serverId;
    const adapter = state.adapter;
    if (serverId === null || adapter === null) {
      throw new Error("Adapter connection was not initialized.");
    }

    const previous = this.#byServer.get(serverId);
    if (previous !== undefined && previous !== state) {
      this.#closeConnection(previous, 1000, "replaced by reconnect");
    }

    this.#core.registerConnection({
      connectionId: state.connectionId,
      serverId,
      adapter,
      capabilities: snapshot,
    });
    this.#byServer.set(serverId, state);
    state.active = true;
    globalThis.clearTimeout(state.handshakeTimer);
    this.#emit("adapter.active", serverId, state.connectionId, null, {
      tools: snapshot.tools,
    });
  }

  async #handleChat(
    state: ConnectionState,
    message: ChatMessageEnvelope,
  ): Promise<void> {
    const adapter = state.adapter;
    if (adapter === null) {
      throw new Error("Adapter bridge is unavailable.");
    }
    adapter.markChat(message);
    this.#emit("chat.message", state.serverId, state.connectionId, message.requestId, {
      requesterUuid: message.requesterUuid,
      mode: message.payload.mode,
    });

    const outcome = await this.#core.handleChat(state.connectionId, message);
    this.#emit("core.outcome", state.serverId, state.connectionId, message.requestId, {
      status: outcome.status,
      code: outcome.status === "REJECTED" ? outcome.code : null,
    });
  }

  #handleToolResult(
    state: ConnectionState,
    result: ToolResultEnvelope,
  ): void {
    const adapter = state.adapter;
    if (adapter === null || !adapter.acceptToolResult(result)) {
      throw new Error("Tool result does not match a pending toolCallId.");
    }
  }

  #handleCancel(
    state: ConnectionState,
    message: JsonObject,
  ): void {
    const payload = message.payload as JsonObject;
    const targetRequestId = payload.targetRequestId as string;
    const reason = payload.reason as string;

    const adapter = state.adapter;
    adapter?.cancelRequest(targetRequestId);

    const requesterUuid = typeof message.requesterUuid === "string" ? message.requesterUuid : null;
    if (
      requesterUuid !== null &&
      (reason === "CLIENT_DISCONNECTED" ||
        reason === "SESSION_ENDED" ||
        reason === "OP_REVOKED")
    ) {
      adapter?.invalidateActor(requesterUuid);
      if (state.serverId !== null) {
        this.#core.invalidateActor(state.serverId, requesterUuid);
      }
    }

    this.#emit("cancel", state.serverId, state.connectionId, targetRequestId, {
      reason,
    });
  }

  async #handlePing(
    state: ConnectionState,
    message: JsonObject,
  ): Promise<void> {
    const payload = message.payload as JsonObject;
    const nonce = payload.nonce as string;
    const adapter = state.adapter;
    if (adapter === null || state.serverId === null) {
      throw new Error("Ping arrived before Adapter activation.");
    }
    await adapter.send({
      protocolVersion: PROTOCOL_VERSION,
      type: "pong",
      messageId: randomUUID(),
      requestId: null,
      serverId: state.serverId,
      sessionId: null,
      requesterUuid: null,
      sentAt: new Date(this.#nowMs()).toISOString(),
      deadlineAt: null,
      payload: { nonce },
    });
  }

  #closeConnection(
    state: ConnectionState,
    code: number,
    reason: string,
  ): void {
    if (state.closed) {
      return;
    }
    this.#detach(state, reason);
    try {
      state.socket.close(code, reason.slice(0, 100));
    } catch {
      try {
        state.socket.terminate();
      } catch {}
    }
  }

  #detach(state: ConnectionState, reason: string): void {
    if (state.closed) {
      return;
    }
    state.closed = true;
    state.active = false;
    globalThis.clearTimeout(state.handshakeTimer);
    this.#connections.delete(state);

    if (state.serverId !== null && this.#byServer.get(state.serverId) === state) {
      this.#byServer.delete(state.serverId);
    }
    state.adapter?.failAll(reason);
    this.#core.unregisterConnection(state.connectionId);
    this.#emit("adapter.disconnected", state.serverId, state.connectionId, null, {
      reason: reason.slice(0, 128),
    });
  }

  #emit(
    kind: string,
    serverId: string | null,
    connectionId: string | null,
    requestId: string | null,
    details: Readonly<Record<string, JsonValue>>,
  ): void {
    const event: BrainServerEvent = {
      at: new Date(this.#nowMs()).toISOString(),
      kind,
      serverId,
      connectionId,
      requestId,
      details,
    };
    try {
      this.#observer.onEvent(event);
    } catch {
      // Observability must never gain execution authority.
    }
  }
}

function parseCapabilities(payload: JsonObject): CapabilitySnapshot {
  const capabilities = (payload.capabilities as readonly JsonObject[]).map(
    (item) => ({
      name: item.name as string,
      source: item.source as string,
      version: typeof item.version === "string" ? item.version : null,
    }),
  );
  const limits = payload.limits as JsonObject;
  return {
    capabilities,
    tools: payload.tools as readonly string[],
    limits: {
      maxMessageBytes: limits.maxMessageBytes as number,
      maxToolCallsPerRequest: limits.maxToolCallsPerRequest as number,
      maxModelRoundTripsPerRequest:
        limits.maxModelRoundTripsPerRequest as number,
    },
  };
}

function parseChatMessage(message: JsonObject): ChatMessageEnvelope {
  return message as unknown as ChatMessageEnvelope;
}

function parseToolResult(message: JsonObject): ToolResultEnvelope {
  return message as unknown as ToolResultEnvelope;
}

function rawToText(raw: RawData): string {
  return raw.toString("utf8");
}

function requestIdOf(raw: unknown): string | null {
  if (raw === null || typeof raw !== "object" || Array.isArray(raw)) {
    return null;
  }
  const value = (raw as Record<string, unknown>).requestId;
  return typeof value === "string" ? value : null;
}

function safeSecretEquals(expected: string, supplied: string): boolean {
  const left = new TextEncoder().encode(expected);
  const right = new TextEncoder().encode(supplied);
  if (left.length !== right.length) {
    return false;
  }
  return timingSafeEqual(left, right);
}

function validateSecret(secret: string): string {
  const value = secret.trim();
  if (value.length < 16 || /\s/.test(value)) {
    throw new Error("Brain shared secret must be at least 16 non-space characters.");
  }
  if (/^(change[_-]?me|changeme|secret|sample|example|test.*)$/i.test(value)) {
    throw new Error("Brain shared secret must not use a placeholder.");
  }
  return value;
}

function isLoopbackHost(host: string): boolean {
  return ["127.0.0.1", "::1", "localhost"].includes(host.toLowerCase());
}

function safeError(error: unknown): string {
  return asError(error).message.slice(0, 256);
}

function asError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error));
}
