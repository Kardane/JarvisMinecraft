import type { JsonObject, JsonValue } from "../core/types.js";
import type { AuditEvent } from "../core/ports.js";

const SENSITIVE_KEY =
  /(secret|token|password|passphrase|authorization|cookie|credential|api[_-]?key|private[_-]?key)/i;

const KEY_VALUE_SECRET =
  /\b(OPENAI_API_KEY|TYPESAFE_API_KEY|JARVIS_SHARED_SECRET)\s*[:=]\s*[^\s,;]+/gi;

const BEARER_SECRET = /\bBearer\s+[A-Za-z0-9._~+\/=:-]{8,}/gi;
const OPENAI_SECRET = /\bsk-[A-Za-z0-9_-]{8,}/g;

export function sanitizeAuditEvent(event: AuditEvent): AuditEvent {
  return {
    ...event,
    argumentSummary: sanitizeJsonObject(event.argumentSummary, 0),
    source: redactString(event.source),
    modelId: redactString(event.modelId),
    fallbackReason:
      event.fallbackReason === null
        ? null
        : redactString(event.fallbackReason),
    capabilities: event.capabilities.slice(0, 64).map((capability) => ({
      name: redactString(capability.name),
      source: redactString(capability.source),
      version:
        capability.version === null
          ? null
          : redactString(capability.version),
    })),
  };
}

export function sanitizeJsonObject(value: JsonObject, depth = 0): JsonObject {
  if (depth >= 6) {
    return {};
  }

  const output: Record<string, JsonValue> = {};
  for (const [key, item] of Object.entries(value).slice(0, 32)) {
    if (SENSITIVE_KEY.test(key)) {
      output[key] = "[redacted]";
      continue;
    }
    output[key] = sanitizeJsonValue(item, depth + 1);
  }
  return output;
}

function sanitizeJsonValue(value: JsonValue, depth: number): JsonValue {
  if (typeof value === "string") {
    return redactString(value);
  }
  if (Array.isArray(value)) {
    return value.slice(0, 32).map((item) => sanitizeJsonValue(item, depth + 1));
  }
  if (value !== null && typeof value === "object") {
    return sanitizeJsonObject(value as JsonObject, depth);
  }
  return value;
}

export function redactString(value: string): string {
  return value
    .replace(KEY_VALUE_SECRET, "$1=[redacted]")
    .replace(BEARER_SECRET, "Bearer [redacted]")
    .replace(OPENAI_SECRET, "[redacted]")
    .slice(0, 512);
}
