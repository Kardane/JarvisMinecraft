import { CoreError } from "./errors.js";

export class RequestBudget {
  static readonly MAX_TOOL_CALLS = 8;
  static readonly MAX_MODEL_ROUNDS = 4;
  static readonly MAX_REQUEST_MS = 30_000;

  readonly deadlineMs: number;
  #toolCalls = 0;
  #modelRounds = 0;

  constructor(adapterDeadlineMs: number, startedAtMs: number) {
    if (!Number.isFinite(adapterDeadlineMs) || !Number.isFinite(startedAtMs)) {
      throw new CoreError("INVALID_ARGUMENT", "Request deadline is invalid.");
    }
    this.deadlineMs = Math.min(
      adapterDeadlineMs,
      startedAtMs + RequestBudget.MAX_REQUEST_MS,
    );
  }

  assertLive(nowMs: number): void {
    if (nowMs >= this.deadlineMs) {
      throw new CoreError("TIMEOUT", "Request deadline has expired.", true);
    }
  }

  consumeModelRound(nowMs: number): void {
    this.assertLive(nowMs);
    if (this.#modelRounds >= RequestBudget.MAX_MODEL_ROUNDS) {
      throw new CoreError("BUSY", "Model round budget is exhausted.");
    }
    this.#modelRounds += 1;
  }

  consumeToolCalls(count: number, nowMs: number): void {
    this.assertLive(nowMs);
    if (!Number.isInteger(count) || count < 1) {
      throw new CoreError("INVALID_ARGUMENT", "Model returned an invalid Tool call batch.");
    }
    if (this.#toolCalls + count > RequestBudget.MAX_TOOL_CALLS) {
      throw new CoreError("BUSY", "Tool call budget is exhausted.");
    }
    this.#toolCalls += count;
  }

  get remainingToolCalls(): number {
    return RequestBudget.MAX_TOOL_CALLS - this.#toolCalls;
  }

  get remainingModelRounds(): number {
    return RequestBudget.MAX_MODEL_ROUNDS - this.#modelRounds;
  }
}
