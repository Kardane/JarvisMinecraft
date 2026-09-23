import { RequestBudget } from "./budget.js";
import {
  getToolDescriptor,
  resolveActiveTools,
  summarizeArguments,
} from "./catalog.js";
import { asCoreError, CoreError } from "./errors.js";
import type {
  AdapterPort,
  AuditEvent,
  AuditPort,
  ModelPort,
  TimePort,
} from "./ports.js";
import { systemTime } from "./ports.js";
import { RequestScheduler } from "./request-scheduler.js";
import { SessionStore } from "./session-store.js";
import type {
  ActorBinding,
  CapabilitySnapshot,
  ChatMessageEnvelope,
  ChatResponseEnvelope,
  CoreOutcome,
  ErrorCode,
  ModelStep,
  ModelToolCall,
  ToolDescriptor,
  ToolName,
  ToolRequestEnvelope,
  ToolResultEnvelope,
} from "./types.js";

interface ConnectionState {
  readonly connectionId: string;
  readonly serverId: string;
  readonly adapter: AdapterPort;
  capabilities: CapabilitySnapshot;
  activeTools: ReadonlyMap<ToolName, ToolDescriptor>;
  active: boolean;
}

export interface ServerConnectionConfig {
  readonly connectionId: string;
  readonly serverId: string;
  readonly adapter: AdapterPort;
  readonly capabilities: CapabilitySnapshot;
}

export interface BrainCoreOptions {
  readonly model: ModelPort;
  readonly audit: AuditPort;
  readonly time?: TimePort;
  readonly sessionTtlMs?: number;
}

export class BrainCore {
  readonly #model: ModelPort;
  readonly #audit: AuditPort;
  readonly #time: TimePort;
  readonly #sessions: SessionStore;
  readonly #scheduler = new RequestScheduler();
  readonly #connectionsById = new Map<string, ConnectionState>();
  readonly #connectionsByServer = new Map<string, ConnectionState>();

  constructor(options: BrainCoreOptions) {
    this.#model = options.model;
    this.#audit = options.audit;
    this.#time = options.time ?? systemTime;
    this.#sessions = new SessionStore(options.sessionTtlMs);
  }

  registerConnection(config: ServerConnectionConfig): void {
    if (config.connectionId.length === 0 || config.serverId.length === 0) {
      throw new CoreError("INVALID_ARGUMENT", "Connection binding is incomplete.");
    }
    if (this.#connectionsById.has(config.connectionId)) {
      throw new CoreError("INVALID_ARGUMENT", "connectionId is already registered.");
    }

    const activeTools = toToolMap(resolveActiveTools(config.capabilities));
    const previous = this.#connectionsByServer.get(config.serverId);
    if (previous !== undefined) {
      previous.active = false;
      this.#connectionsById.delete(previous.connectionId);
      this.#scheduler.cancelServer(config.serverId);
      this.#sessions.invalidateServer(config.serverId);
    }

    const state: ConnectionState = {
      connectionId: config.connectionId,
      serverId: config.serverId,
      adapter: config.adapter,
      capabilities: config.capabilities,
      activeTools,
      active: true,
    };
    this.#connectionsById.set(config.connectionId, state);
    this.#connectionsByServer.set(config.serverId, state);
  }

  updateCapabilities(
    connectionId: string,
    capabilities: CapabilitySnapshot,
  ): void {
    const connection = this.requireConnection(connectionId);
    connection.activeTools = toToolMap(resolveActiveTools(capabilities));
    connection.capabilities = capabilities;
  }

  unregisterConnection(connectionId: string): void {
    const connection = this.#connectionsById.get(connectionId);
    if (connection === undefined) {
      return;
    }
    connection.active = false;
    this.#connectionsById.delete(connectionId);
    if (this.#connectionsByServer.get(connection.serverId) === connection) {
      this.#connectionsByServer.delete(connection.serverId);
    }
    this.#scheduler.cancelServer(connection.serverId);
    this.#sessions.invalidateServer(connection.serverId);
  }

  invalidateActor(serverId: string, requesterUuid: string): void {
    this.#sessions.invalidateActor(serverId, requesterUuid);
  }

  schedulerSnapshot() {
    return this.#scheduler.snapshot();
  }

  async handleChat(
    connectionId: string,
    message: ChatMessageEnvelope,
  ): Promise<CoreOutcome> {
    let connection: ConnectionState | undefined;
    let sessionKeyValue: string | undefined;

    try {
      connection = this.requireConnection(connectionId, message.serverId);
      this.validateChatMessage(message);

      const binding = bindingFrom(message);
      if (!(await this.checkOperator(connection, binding))) {
        this.#sessions.invalidateActor(message.serverId, message.requesterUuid);
        return { status: "REJECTED", code: "UNAUTHORIZED" };
      }

      const accepted = this.#sessions.accept(message, this.#time.nowMs());
      sessionKeyValue = accepted.key;

      return await this.#scheduler.submit(
        message.serverId,
        accepted.key,
        async () =>
          await this.processRequest(
            connection as ConnectionState,
            accepted.key,
            message,
          ),
      );
    } catch (error) {
      const coreError = asCoreError(error);
      if (connection !== undefined && sessionKeyValue !== undefined) {
        const response = await this.tryDeliverError(
          connection,
          sessionKeyValue,
          message,
          coreError,
        );
        if (response !== null) {
          return { status: "RESPONDED", response };
        }
      }
      return { status: "REJECTED", code: coreError.code };
    }
  }

  private async processRequest(
    connection: ConnectionState,
    sessionKeyValue: string,
    message: ChatMessageEnvelope,
  ): Promise<CoreOutcome> {
    this.assertConnectionCurrent(connection);
    this.assertSessionActive(sessionKeyValue);
    const binding = bindingFrom(message);
    await this.assertOperator(connection, binding);

    const adapterDeadlineMs = parseDate(message.deadlineAt, "deadlineAt");
    const budget = new RequestBudget(adapterDeadlineMs, this.#time.nowMs());
    budget.assertLive(this.#time.nowMs());

    this.#sessions.append(
      sessionKeyValue,
      {
        role: "user",
        text: message.payload.text,
        requestId: message.requestId,
        at: nowIso(this.#time),
      },
      this.#time.nowMs(),
    );

    while (true) {
      this.assertConnectionCurrent(connection);
      this.assertSessionActive(sessionKeyValue);
      await this.assertOperator(connection, binding);
      budget.consumeModelRound(this.#time.nowMs());

      const step = await this.withDeadline(
        this.#model.next({
          binding,
          requesterName: message.payload.requesterName,
          history: this.#sessions.history(sessionKeyValue, this.#time.nowMs()),
          capabilities: connection.capabilities.capabilities,
          availableTools: [...connection.activeTools.values()],
          remainingToolCalls: budget.remainingToolCalls,
          remainingModelRounds: budget.remainingModelRounds,
          deadlineAt: new Date(budget.deadlineMs).toISOString(),
        }),
        budget.deadlineMs,
        "TIMEOUT",
        "Model response exceeded the request deadline.",
      );

      if (step.kind === "final") {
        this.validateFinalStep(step);
        this.assertConnectionCurrent(connection);
        this.assertSessionActive(sessionKeyValue);
        await this.assertOperator(connection, binding);

        const response = this.createResponse(message, step.text, step.sessionState);
        await this.withDeadline(
          connection.adapter.deliverResponse(response),
          Math.min(budget.deadlineMs, this.#time.nowMs() + 5_000),
          "TIMEOUT",
          "Response delivery timed out.",
        );

        this.#sessions.append(
          sessionKeyValue,
          {
            role: "assistant",
            text: step.text,
            requestId: message.requestId,
            at: nowIso(this.#time),
          },
          this.#time.nowMs(),
        );

        if (step.sessionState === "END") {
          this.#sessions.end(sessionKeyValue);
        }

        return { status: "RESPONDED", response };
      }

      if (step.calls.length === 0) {
        throw new CoreError(
          "INVALID_ARGUMENT",
          "Model returned an empty Tool call batch.",
        );
      }

      budget.consumeToolCalls(step.calls.length, this.#time.nowMs());
      for (const call of step.calls) {
        await this.executeModelTool(
          connection,
          sessionKeyValue,
          binding,
          message,
          budget,
          call,
        );
      }
    }
  }

  private async executeModelTool(
    connection: ConnectionState,
    sessionKeyValue: string,
    binding: ActorBinding,
    message: ChatMessageEnvelope,
    budget: RequestBudget,
    call: ModelToolCall,
  ): Promise<void> {
    this.assertConnectionCurrent(connection);
    this.assertSessionActive(sessionKeyValue);
    const descriptor = getToolDescriptor(call.tool);
    const active = connection.activeTools.get(descriptor.name);
    if (active === undefined) {
      throw new CoreError(
        "UNSUPPORTED",
        "Model requested a Tool that is not active on this server.",
      );
    }

    await this.assertOperator(connection, binding);
    budget.assertLive(this.#time.nowMs());

    const toolCallId = randomId();
    const actionId = descriptor.stateChanging ? randomId() : null;
    const toolDeadlineMs = Math.min(
      budget.deadlineMs,
      this.#time.nowMs() + 5_000,
    );
    const request: ToolRequestEnvelope = {
      protocolVersion: "1.0",
      type: "tool.request",
      messageId: randomId(),
      requestId: message.requestId,
      serverId: message.serverId,
      sessionId: message.sessionId,
      requesterUuid: message.requesterUuid,
      sentAt: nowIso(this.#time),
      deadlineAt: new Date(toolDeadlineMs).toISOString(),
      payload: {
        tool: descriptor.name,
        arguments: call.arguments,
      },
      toolCallId,
      actionId,
    };

    if (descriptor.stateChanging) {
      const recorded = await this.withDeadline(
        this.#audit.record(
          this.auditEvent(
            connection,
            message,
            descriptor,
            toolCallId,
            actionId,
            call.arguments,
            "PRE_EXECUTION",
            "BrainPolicy",
            0,
          ),
        ),
        Math.min(toolDeadlineMs, this.#time.nowMs() + 2_000),
        "INTERNAL",
        "Pre-execution audit could not be confirmed.",
      );
      if (!recorded) {
        throw new CoreError(
          "INTERNAL",
          "State-changing Tool was refused because audit is unavailable.",
        );
      }
    }

    this.assertConnectionCurrent(connection);
    this.assertSessionActive(sessionKeyValue);
    await this.assertOperator(connection, binding);

    const startedAt = this.#time.nowMs();
    const result = await this.withDeadline(
      connection.adapter.executeTool(request),
      toolDeadlineMs,
      descriptor.stateChanging ? "OUTCOME_UNKNOWN" : "TIMEOUT",
      descriptor.stateChanging
        ? "State-changing Tool outcome could not be confirmed."
        : "Tool execution timed out.",
    );

    this.validateToolResult(request, result);
    this.assertConnectionCurrent(connection);
    this.assertSessionActive(sessionKeyValue);

    await this.tryPostAudit(
      this.auditEvent(
        connection,
        message,
        descriptor,
        toolCallId,
        actionId,
        call.arguments,
        result.payload.result.status,
        result.payload.result.source,
        Math.max(0, this.#time.nowMs() - startedAt),
      ),
      budget.deadlineMs,
    );

    this.#sessions.append(
      sessionKeyValue,
      {
        role: "tool",
        tool: descriptor.name,
        toolCallId,
        result: result.payload.result,
        requestId: message.requestId,
        at: nowIso(this.#time),
      },
      this.#time.nowMs(),
    );
  }

  private auditEvent(
    connection: ConnectionState,
    message: ChatMessageEnvelope,
    descriptor: ToolDescriptor,
    toolCallId: string,
    actionId: string | null,
    argumentsObject: ModelToolCall["arguments"],
    outcome: string,
    source: string,
    latencyMs: number,
  ): AuditEvent {
    return {
      timestamp: nowIso(this.#time),
      serverId: message.serverId,
      requesterUuid: message.requesterUuid,
      requestId: message.requestId,
      toolCallId,
      actionId,
      tool: descriptor.name,
      risk: descriptor.risk,
      argumentSummary: summarizeArguments(argumentsObject),
      outcome,
      source,
      latencyMs,
      modelId: this.#model.modelId,
      fallbackReason: null,
      capabilities: connection.capabilities.capabilities,
    };
  }

  private async tryPostAudit(event: AuditEvent, requestDeadlineMs: number) {
    try {
      await this.withDeadline(
        this.#audit.record(event),
        Math.min(requestDeadlineMs, this.#time.nowMs() + 1_000),
        "INTERNAL",
        "Post-execution audit timed out.",
      );
    } catch {
      // The Tool has already completed. Never retry or rewrite its outcome here.
    }
  }

  private validateToolResult(
    request: ToolRequestEnvelope,
    result: ToolResultEnvelope,
  ): void {
    if (
      result.protocolVersion !== "1.0" ||
      result.type !== "tool.result" ||
      result.requestId !== request.requestId ||
      result.serverId !== request.serverId ||
      result.sessionId !== request.sessionId ||
      result.requesterUuid !== request.requesterUuid ||
      result.toolCallId !== request.toolCallId ||
      result.actionId !== request.actionId ||
      result.payload.tool !== request.payload.tool
    ) {
      throw new CoreError(
        "UNAUTHORIZED",
        "Tool result does not match the active actor/request binding.",
      );
    }
  }

  private validateChatMessage(message: ChatMessageEnvelope): void {
    if (message.protocolVersion !== "1.0" || message.type !== "chat.message") {
      throw new CoreError("UNSUPPORTED", "Unsupported Brain protocol message.");
    }
    if (
      message.serverId.length < 1 ||
      message.serverId.length > 64 ||
      !isUuid(message.messageId) ||
      !isUuid(message.requestId) ||
      !isUuid(message.sessionId) ||
      !isUuid(message.requesterUuid)
    ) {
      throw new CoreError("INVALID_ARGUMENT", "Chat message binding is invalid.");
    }
    if (
      message.payload.requesterName.length < 1 ||
      message.payload.requesterName.length > 16 ||
      message.payload.text.length < 1 ||
      message.payload.text.length > 4_096
    ) {
      throw new CoreError("INVALID_ARGUMENT", "Chat payload is outside protocol limits.");
    }

    const sentAt = parseDate(message.sentAt, "sentAt");
    const deadlineAt = parseDate(message.deadlineAt, "deadlineAt");
    if (deadlineAt <= sentAt) {
      throw new CoreError("INVALID_ARGUMENT", "deadlineAt must be after sentAt.");
    }
    if (this.#time.nowMs() >= deadlineAt) {
      throw new CoreError("TIMEOUT", "Chat request deadline has expired.", true);
    }
  }

  private validateFinalStep(step: Extract<ModelStep, { kind: "final" }>): void {
    if (step.text.length < 1 || step.text.length > 12_000) {
      throw new CoreError("INVALID_ARGUMENT", "Model final text is outside protocol limits.");
    }
    if (step.sessionState !== "CONTINUE" && step.sessionState !== "END") {
      throw new CoreError("INVALID_ARGUMENT", "Model returned an invalid session state.");
    }
  }

  private requireConnection(
    connectionId: string,
    expectedServerId?: string,
  ): ConnectionState {
    const connection = this.#connectionsById.get(connectionId);
    if (
      connection === undefined ||
      !connection.active ||
      this.#connectionsByServer.get(connection.serverId) !== connection
    ) {
      throw new CoreError("UNAUTHORIZED", "Adapter connection is not active.");
    }
    if (
      expectedServerId !== undefined &&
      expectedServerId !== connection.serverId
    ) {
      throw new CoreError(
        "UNAUTHORIZED",
        "serverId does not match the authenticated connection.",
      );
    }
    return connection;
  }

  private assertConnectionCurrent(connection: ConnectionState): void {
    if (
      !connection.active ||
      this.#connectionsById.get(connection.connectionId) !== connection ||
      this.#connectionsByServer.get(connection.serverId) !== connection
    ) {
      throw new CoreError("CANCELLED", "Adapter connection is no longer current.");
    }
  }

  private assertSessionActive(sessionKeyValue: string): void {
    if (!this.#sessions.isActive(sessionKeyValue, this.#time.nowMs())) {
      throw new CoreError("CANCELLED", "Conversation session is no longer active.");
    }
  }

  private async checkOperator(
    connection: ConnectionState,
    binding: ActorBinding,
  ): Promise<boolean> {
    try {
      return await connection.adapter.isCurrentOperator(binding);
    } catch {
      return false;
    }
  }

  private async assertOperator(
    connection: ConnectionState,
    binding: ActorBinding,
  ): Promise<void> {
    if (!(await this.checkOperator(connection, binding))) {
      this.#sessions.invalidateActor(binding.serverId, binding.requesterUuid);
      throw new CoreError(
        "UNAUTHORIZED",
        "Requester is not a current online operator.",
      );
    }
  }

  private createResponse(
    message: ChatMessageEnvelope,
    text: string,
    sessionState: "CONTINUE" | "END",
  ): ChatResponseEnvelope {
    const now = this.#time.nowMs();
    return {
      protocolVersion: "1.0",
      type: "chat.response",
      messageId: randomId(),
      requestId: message.requestId,
      serverId: message.serverId,
      sessionId: message.sessionId,
      requesterUuid: message.requesterUuid,
      sentAt: new Date(now).toISOString(),
      deadlineAt: new Date(now + 5_000).toISOString(),
      payload: {
        text,
        final: true,
        sessionState,
      },
    };
  }

  private async tryDeliverError(
    connection: ConnectionState,
    sessionKeyValue: string,
    message: ChatMessageEnvelope,
    error: CoreError,
  ): Promise<ChatResponseEnvelope | null> {
    if (error.code === "UNAUTHORIZED" || error.code === "CANCELLED") {
      return null;
    }

    try {
      this.assertConnectionCurrent(connection);
      this.assertSessionActive(sessionKeyValue);
      if (!(await this.checkOperator(connection, bindingFrom(message)))) {
        this.#sessions.invalidateActor(message.serverId, message.requesterUuid);
        return null;
      }

      const response = this.createResponse(
        message,
        safeErrorText(error.code),
        "CONTINUE",
      );
      await connection.adapter.deliverResponse(response);
      this.#sessions.append(
        sessionKeyValue,
        {
          role: "assistant",
          text: response.payload.text,
          requestId: message.requestId,
          at: nowIso(this.#time),
        },
        this.#time.nowMs(),
      );
      return response;
    } catch {
      return null;
    }
  }

  private async withDeadline<T>(
    promise: Promise<T>,
    deadlineMs: number,
    code: ErrorCode,
    message: string,
  ): Promise<T> {
    const remaining = deadlineMs - this.#time.nowMs();
    if (remaining <= 0) {
      throw new CoreError(code, message, code === "TIMEOUT");
    }

    return await new Promise<T>((resolve, reject) => {
      const timer = globalThis.setTimeout(() => {
        reject(new CoreError(code, message, code === "TIMEOUT"));
      }, remaining);

      promise.then(
        (value) => {
          globalThis.clearTimeout(timer);
          resolve(value);
        },
        (error) => {
          globalThis.clearTimeout(timer);
          reject(error);
        },
      );
    });
  }
}

function toToolMap(
  descriptors: readonly ToolDescriptor[],
): ReadonlyMap<ToolName, ToolDescriptor> {
  return new Map(descriptors.map((descriptor) => [descriptor.name, descriptor]));
}

function bindingFrom(message: ChatMessageEnvelope): ActorBinding {
  return {
    serverId: message.serverId,
    requesterUuid: message.requesterUuid,
    sessionId: message.sessionId,
    requestId: message.requestId,
  };
}

function nowIso(time: TimePort): string {
  return new Date(time.nowMs()).toISOString();
}

function parseDate(value: string, field: string): number {
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) {
    throw new CoreError("INVALID_ARGUMENT", field + " is not a valid date-time.");
  }
  return parsed;
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
    value,
  );
}

function randomId(): string {
  if (globalThis.crypto === undefined) {
    throw new CoreError("INTERNAL", "Secure UUID generator is unavailable.");
  }
  return globalThis.crypto.randomUUID();
}

function safeErrorText(code: ErrorCode): string {
  switch (code) {
    case "BUSY":
      return "자비스가 현재 처리 가능한 요청 한도에 도달했습니다. 잠시 후 다시 말해 주세요.";
    case "TIMEOUT":
      return "요청을 제한 시간 안에 완료하지 못했습니다. 다시 시도해 주세요.";
    case "OUTCOME_UNKNOWN":
      return "작업 결과를 안전하게 확인할 수 없습니다. 자동으로 다시 실행하지 않았습니다.";
    case "UNSUPPORTED":
      return "현재 이 서버에서는 해당 작업을 사용할 수 없습니다.";
    case "INVALID_ARGUMENT":
    case "AMBIGUOUS_TARGET":
    case "NOT_FOUND":
      return "요청을 안전하게 확정할 수 없습니다. 대상을 더 명확하게 말해 주세요.";
    case "PROVIDER_UNAVAILABLE":
      return "필요한 서버 기능이 현재 응답하지 않습니다.";
    case "INTERNAL":
      return "자비스 처리 중 내부 문제가 발생했습니다.";
    case "UNAUTHORIZED":
    case "CANCELLED":
      return "요청을 계속 처리할 수 없습니다.";
  }
}
