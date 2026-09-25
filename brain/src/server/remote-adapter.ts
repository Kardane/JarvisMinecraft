import { PROTOCOL_LIMITS } from "../generated/contract-constants.js";
import { assertProtocolMessage } from "../protocol/schema-validator.js";
import type { AdapterPort } from "../core/ports.js";
import type {
  ActorBinding,
  ChatMessageEnvelope,
  ChatResponseEnvelope,
  JsonObject,
  ToolRequestEnvelope,
  ToolResultEnvelope,
} from "../core/types.js";
import { WebSocket } from "ws";

interface PendingTool {
  readonly request: ToolRequestEnvelope;
  readonly resolve: (result: ToolResultEnvelope) => void;
  readonly reject: (error: Error) => void;
  readonly timer: ReturnType<typeof globalThis.setTimeout>;
}

export type RemoteAdapterEventSink = (
  kind: string,
  requestId: string | null,
  details: JsonObject,
) => void;

export class RemoteAdapter implements AdapterPort {
  readonly #socket: WebSocket;
  readonly #serverId: string;
  readonly #emit: RemoteAdapterEventSink;
  readonly #bindings = new Map<string, ActorBinding>();
  readonly #pending = new Map<string, PendingTool>();
  #active = true;

  constructor(
    socket: WebSocket,
    serverId: string,
    emit: RemoteAdapterEventSink,
  ) {
    this.#socket = socket;
    this.#serverId = serverId;
    this.#emit = emit;
  }

  markChat(message: ChatMessageEnvelope): void {
    if (message.serverId !== this.#serverId) {
      return;
    }
    this.#bindings.set(message.requestId, {
      serverId: message.serverId,
      requesterUuid: message.requesterUuid,
      sessionId: message.sessionId,
      requestId: message.requestId,
    });
  }

  cancelRequest(requestId: string): void {
    this.#bindings.delete(requestId);
    for (const [toolCallId, pending] of this.#pending) {
      if (pending.request.requestId !== requestId) {
        continue;
      }
      globalThis.clearTimeout(pending.timer);
      this.#pending.delete(toolCallId);
      pending.reject(new Error("Adapter cancelled the active request."));
    }
  }

  invalidateActor(requesterUuid: string): void {
    for (const [requestId, binding] of this.#bindings) {
      if (binding.requesterUuid === requesterUuid) {
        this.cancelRequest(requestId);
      }
    }
  }

  async isCurrentOperator(binding: ActorBinding): Promise<boolean> {
    if (!this.#active || this.#socket.readyState !== WebSocket.OPEN) {
      return false;
    }
    const accepted = this.#bindings.get(binding.requestId);
    return (
      accepted !== undefined &&
      accepted.serverId === binding.serverId &&
      accepted.requesterUuid === binding.requesterUuid &&
      accepted.sessionId === binding.sessionId
    );
  }

  async executeTool(
    request: ToolRequestEnvelope,
  ): Promise<ToolResultEnvelope> {
    if (!this.#active || this.#socket.readyState !== WebSocket.OPEN) {
      throw new Error("Adapter connection is not active.");
    }
    if (!(await this.isCurrentOperator({
      serverId: request.serverId,
      requesterUuid: request.requesterUuid,
      sessionId: request.sessionId,
      requestId: request.requestId,
    }))) {
      throw new Error("Tool request is not bound to an active Adapter request.");
    }
    if (this.#pending.has(request.toolCallId)) {
      throw new Error("Duplicate toolCallId is already pending.");
    }

    const deadline = Date.parse(request.deadlineAt);
    const remaining = deadline - Date.now();
    if (!Number.isFinite(remaining) || remaining <= 0) {
      throw new Error("Tool request deadline has expired.");
    }

    const result = new Promise<ToolResultEnvelope>((resolve, reject) => {
      const timer = globalThis.setTimeout(() => {
        this.#pending.delete(request.toolCallId);
        reject(new Error("Tool result deadline expired."));
      }, remaining);

      this.#pending.set(request.toolCallId, {
        request,
        resolve,
        reject,
        timer,
      });
    });

    this.#emit("tool.request", request.requestId, {
      tool: request.payload.tool,
      toolCallId: request.toolCallId,
      actionId: request.actionId,
    });
    await this.send(request);
    return await result;
  }

  async deliverResponse(response: ChatResponseEnvelope): Promise<void> {
    if (!this.#active || this.#socket.readyState !== WebSocket.OPEN) {
      throw new Error("Adapter connection is not active.");
    }
    if (!(await this.isCurrentOperator({
      serverId: response.serverId,
      requesterUuid: response.requesterUuid,
      sessionId: response.sessionId,
      requestId: response.requestId,
    }))) {
      throw new Error("Response is not bound to an active Adapter request.");
    }

    this.#emit("chat.response", response.requestId, {
      requesterUuid: response.requesterUuid,
      sessionState: response.payload.sessionState,
    });
    try {
      await this.send(response);
    } finally {
      if (response.payload.final) {
        this.#bindings.delete(response.requestId);
      }
    }
  }

  acceptToolResult(result: ToolResultEnvelope): boolean {
    const pending = this.#pending.get(result.toolCallId);
    if (pending === undefined) {
      return false;
    }

    this.#pending.delete(result.toolCallId);
    globalThis.clearTimeout(pending.timer);
    this.#emit("tool.result", result.requestId, {
      tool: result.payload.tool,
      toolCallId: result.toolCallId,
      status: result.payload.result.status,
    });
    pending.resolve(result);
    return true;
  }

  failAll(reason: string): void {
    if (!this.#active) {
      return;
    }
    this.#active = false;
    this.#bindings.clear();
    for (const pending of this.#pending.values()) {
      globalThis.clearTimeout(pending.timer);
      pending.reject(new Error(reason));
    }
    this.#pending.clear();
  }

  async send(message: unknown): Promise<void> {
    if (!this.#active || this.#socket.readyState !== WebSocket.OPEN) {
      throw new Error("Adapter WebSocket is not open.");
    }
    assertProtocolMessage(message);
    const raw = JSON.stringify(message);
    const bytes = new TextEncoder().encode(raw).byteLength;
    if (bytes > PROTOCOL_LIMITS.maxMessageBytes) {
      throw new Error(`Outbound protocol message exceeds ${PROTOCOL_LIMITS.maxMessageBytes} bytes.`);
    }

    await new Promise<void>((resolve, reject) => {
      this.#socket.send(raw, (error) => {
        if (error != null) {
          reject(error);
        } else {
          resolve();
        }
      });
    });
  }
}
