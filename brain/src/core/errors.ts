import type { ErrorCode } from "./types.js";

export class CoreError extends Error {
  readonly code: ErrorCode;
  readonly retryable: boolean;

  constructor(code: ErrorCode, message: string, retryable = false) {
    super(message);
    this.name = "CoreError";
    this.code = code;
    this.retryable = retryable;
  }
}

export function asCoreError(error: unknown): CoreError {
  if (error instanceof CoreError) {
    return error;
  }
  return new CoreError("INTERNAL", "Internal Brain failure.");
}
