import { randomUUID, timingSafeEqual } from "node:crypto";
import {
  WebSocket,
  WebSocketServer,
  type RawData,
  type VerifyClientInfo,
} from "ws";

import type { BrainCore } from "../core/brain-core.js";
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

const MAX_MESSAGE_BYTES = 65_536;
const SERVER_ID = /^[A-Za-z0-9._-]{1,64}$/;
const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

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
          maxPayload: MAX_MESSAGE_BYTES,
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
        if (error !== undefined) {
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
      if (new TextEncoder().encode(text).byteLength > MAX_MESSAGE_BYTES) {
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
    const message = requireObject(raw, "protocol message");
    validateCommonEnvelope(message);

    if (state.serverId === null) {
      await this.#handleHello(state, message);
      return;
    }

    const serverId = requireString(message, "serverId", 1, 64);
    if (serverId !== state.serverId) {
      throw new Error("serverId does not match authenticated connection.");
    }

    if (!state.active) {
      await this.#handleCapabilities(state, message);
      return;
    }

    const type = requireString(message, "type", 1, 64);
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
    if (requireString(message, "type", 1, 64) !== "hello") {
      throw new Error("Adapter hello must be the first protocol message.");
    }
    const serverId = requireString(message, "serverId", 1, 64);
    if (!SERVER_ID.test(serverId)) {
      throw new Error("Adapter serverId is invalid.");
    }

    requireNull(message, "requestId");
    requireNull(message, "sessionId");
    requireNull(message, "requesterUuid");
    requireNullOrMissing(message, "deadlineAt");

    const payload = requireObject(message.payload, "hello payload");
    if (requireString(payload, "side", 1, 16) !== "adapter") {
      throw new Error("Hello side must be adapter.");
    }
    const platform = requireString(payload, "platform", 1, 32);
    if (!["paper", "fabric", "neoforge"].includes(platform)) {
      throw new Error("Adapter platform is unsupported.");
    }
    if (requireString(payload, "minecraftVersion", 1, 32) !== "1.21.8") {
      throw new Error("Adapter Minecraft version is unsupported.");
    }
    requireUuid(payload, "adapterInstanceId");
    requireString(payload, "adapterVersion", 1, 128);
    requireString(payload, "platformVersion", 1, 128);

    state.serverId = serverId;
    state.adapter = new RemoteAdapter(
      state.socket,
      serverId,
      (kind, requestId, details) =>
        this.#emit(kind, serverId, state.connectionId, requestId, details),
    );

    await state.adapter.send({
      protocolVersion: "1.0",
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
    if (requireString(message, "type", 1, 64) !== "capabilities") {
      throw new Error("Capabilities must follow the Brain hello.");
    }
    requireNull(message, "requestId");
    requireNull(message, "sessionId");
    requireNull(message, "requesterUuid");
    requireNullOrMissing(message, "deadlineAt");

    const snapshot = parseCapabilities(requireObject(message.payload, "capabilities payload"));
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
    const payload = requireObject(message.payload, "cancel payload");
    const targetRequestId = requireUuid(payload, "targetRequestId");
    const reason = requireString(payload, "reason", 1, 64);
    if (
      ![
        "CLIENT_DISCONNECTED",
        "SESSION_ENDED",
        "DEADLINE_EXCEEDED",
        "OP_REVOKED",
        "SHUTDOWN",
      ].includes(reason)
    ) {
      throw new Error("Cancel reason is invalid.");
    }

    const adapter = state.adapter;
    adapter?.cancelRequest(targetRequestId);

    const requesterUuid = nullableString(message.requesterUuid);
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
    const payload = requireObject(message.payload, "ping payload");
    const nonce = requireString(payload, "nonce", 1, 128);
    const adapter = state.adapter;
    if (adapter === null || state.serverId === null) {
      throw new Error("Ping arrived before Adapter activation.");
    }
    await adapter.send({
      protocolVersion: "1.0",
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
  const rawCapabilities = requireArray(payload, "capabilities", 64);
  const capabilities = rawCapabilities.map((entry) => {
    const item = requireObject(entry, "capability");
    return {
      name: requireString(item, "name", 1, 128),
      source: requireString(item, "source", 1, 128),
      version: nullableString(item.version),
    };
  });

  const rawTools = requireArray(payload, "tools", 32);
  const tools = rawTools.map((tool) => {
    if (typeof tool !== "string" || tool.length < 1 || tool.length > 128) {
      throw new Error("Capability Tool name is invalid.");
    }
    return tool;
  });

  const limits = requireObject(payload.limits, "capability limits");
  return {
    capabilities,
    tools,
    limits: {
      maxMessageBytes: requireInteger(limits, "maxMessageBytes", 1, MAX_MESSAGE_BYTES),
      maxToolCallsPerRequest: requireInteger(limits, "maxToolCallsPerRequest", 1, 64),
      maxModelRoundTripsPerRequest: requireInteger(limits, "maxModelRoundTripsPerRequest", 1, 32),
    },
  };
}

function parseChatMessage(message: JsonObject): ChatMessageEnvelope {
  if (requireString(message, "type", 1, 64) !== "chat.message") {
    throw new Error("Expected chat.message.");
  }
  const payload = requireObject(message.payload, "chat payload");
  const mode = requireString(payload, "mode", 1, 32);
  if (mode !== "DIRECT" && mode !== "FOLLOW_UP") {
    throw new Error("Chat mode is invalid.");
  }

  return {
    protocolVersion: "1.0",
    type: "chat.message",
    messageId: requireUuid(message, "messageId"),
    requestId: requireUuid(message, "requestId"),
    serverId: requireString(message, "serverId", 1, 64),
    sessionId: requireUuid(message, "sessionId"),
    requesterUuid: requireUuid(message, "requesterUuid"),
    sentAt: requireDate(message, "sentAt"),
    deadlineAt: requireDate(message, "deadlineAt"),
    payload: {
      requesterName: requireString(payload, "requesterName", 1, 16),
      text: requireString(payload, "text", 1, 4_096),
      mode,
    },
  };
}

function parseToolResult(message: JsonObject): ToolResultEnvelope {
  if (requireString(message, "type", 1, 64) !== "tool.result") {
    throw new Error("Expected tool.result.");
  }
  const payload = requireObject(message.payload, "tool result payload");
  const result = requireObject(payload.result, "tool result");
  const status = requireString(result, "status", 1, 32);
  if (!["OK", "EMPTY", "ERROR", "UNSUPPORTED"].includes(status)) {
    throw new Error("Tool result status is invalid.");
  }
  const truncated = result.truncated;
  if (typeof truncated !== "boolean") {
    throw new Error("Tool result truncated must be boolean.");
  }
  if (!isJsonValue(result.data) || !isJsonValue(result.error)) {
    throw new Error("Tool result contains a non-JSON value.");
  }

  const actionId = nullableUuid(message.actionId, "actionId");
  return {
    protocolVersion: "1.0",
    type: "tool.result",
    messageId: requireUuid(message, "messageId"),
    requestId: requireUuid(message, "requestId"),
    serverId: requireString(message, "serverId", 1, 64),
    sessionId: requireUuid(message, "sessionId"),
    requesterUuid: requireUuid(message, "requesterUuid"),
    sentAt: requireDate(message, "sentAt"),
    deadlineAt: requireDate(message, "deadlineAt"),
    payload: {
      tool: requireString(payload, "tool", 1, 128) as ToolResultEnvelope["payload"]["tool"],
      result: {
        status: status as ToolResultEnvelope["payload"]["result"]["status"],
        data: result.data as JsonValue | null,
        error: result.error as ToolResultEnvelope["payload"]["result"]["error"],
        observedAt: requireDate(result, "observedAt"),
        source: requireString(result, "source", 1, 128),
        truncated,
      },
    },
    toolCallId: requireUuid(message, "toolCallId"),
    actionId,
  };
}

function validateCommonEnvelope(message: JsonObject): void {
  if (message.protocolVersion !== "1.0") {
    throw new Error("Unsupported protocol version.");
  }
  requireString(message, "type", 1, 64);
  requireUuid(message, "messageId");
  const serverId = requireString(message, "serverId", 1, 64);
  if (!SERVER_ID.test(serverId)) {
    throw new Error("Protocol serverId is invalid.");
  }
  requireDate(message, "sentAt");
}

function requireObject(value: unknown, field: string): JsonObject {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(field + " must be an object.");
  }
  if (!isJsonValue(value)) {
    throw new Error(field + " must contain JSON values only.");
  }
  return value as JsonObject;
}

function requireArray(
  object: JsonObject,
  field: string,
  max: number,
): readonly JsonValue[] {
  const value = object[field];
  if (!Array.isArray(value) || value.length > max) {
    throw new Error(field + " must be a bounded array.");
  }
  return value;
}

function requireString(
  object: JsonObject,
  field: string,
  min: number,
  max: number,
): string {
  const value = object[field];
  if (typeof value !== "string" || value.length < min || value.length > max) {
    throw new Error(field + " must be a bounded string.");
  }
  return value;
}

function requireInteger(
  object: JsonObject,
  field: string,
  min: number,
  max: number,
): number {
  const value = object[field];
  if (!Number.isSafeInteger(value) || typeof value !== "number" || value < min || value > max) {
    throw new Error(field + " must be a bounded integer.");
  }
  return value;
}

function requireUuid(object: JsonObject, field: string): string {
  const value = requireString(object, field, 36, 36);
  if (!UUID.test(value)) {
    throw new Error(field + " must be a UUID.");
  }
  return value;
}

function nullableUuid(value: JsonValue | undefined, field: string): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value !== "string" || !UUID.test(value)) {
    throw new Error(field + " must be UUID or null.");
  }
  return value;
}

function requireDate(object: JsonObject, field: string): string {
  const value = requireString(object, field, 1, 64);
  if (!Number.isFinite(Date.parse(value))) {
    throw new Error(field + " must be a date-time.");
  }
  return value;
}

function requireNull(object: JsonObject, field: string): void {
  if (object[field] !== null) {
    throw new Error(field + " must be null.");
  }
}

function requireNullOrMissing(object: JsonObject, field: string): void {
  const value = object[field];
  if (value !== null && value !== undefined) {
    throw new Error(field + " must be null.");
  }
}

function nullableString(value: JsonValue | undefined): string | null {
  return typeof value === "string" ? value : null;
}

function isJsonValue(value: unknown, depth = 0): value is JsonValue {
  if (depth > 12) {
    return false;
  }
  if (
    value === null ||
    typeof value === "string" ||
    typeof value === "boolean"
  ) {
    return true;
  }
  if (typeof value === "number") {
    return Number.isFinite(value);
  }
  if (Array.isArray(value)) {
    return value.length <= 1_000 && value.every((item) => isJsonValue(item, depth + 1));
  }
  if (typeof value === "object") {
    const entries = Object.entries(value);
    return entries.length <= 1_000 && entries.every(
      ([key, item]) => key.length <= 256 && isJsonValue(item, depth + 1),
    );
  }
  return false;
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
