import type { OpsConfig, OpsPolicy } from "./types.js";

export const DEFAULT_AUDIT_RETENTION_DAYS = 7;
export const DEFAULT_AUDIT_MAX_TOTAL_BYTES = 100 * 1024 * 1024;
export const DEFAULT_AUDIT_MAX_FILE_BYTES = 8 * 1024 * 1024;
export const DEFAULT_AUDIT_MAX_QUEUE = 512;

export const FIXED_V01_POLICY: OpsPolicy = {
  opOnly: true,
  replyRequesterOnly: true,
  warningEnabled: false,
  rollbackEnabled: false,
};

const LOOPBACK_HOSTS = new Set(["127.0.0.1", "::1", "localhost"]);

export function loadOpsConfig(
  env: Readonly<Record<string, string | undefined>>,
): OpsConfig {
  const openaiApiKey = requiredSecret(env, "OPENAI_API_KEY");
  const typesafeApiKey = requiredSecret(env, "TYPESAFE_API_KEY");
  const sharedSecret = requiredSecret(env, "JARVIS_SHARED_SECRET");

  const bindHost = optional(env, "JARVIS_BRAIN_HOST", "127.0.0.1");
  if (!LOOPBACK_HOSTS.has(bindHost.toLowerCase())) {
    throw new Error("JARVIS_BRAIN_HOST must be a loopback host.");
  }

  const bindPort = boundedInteger(
    env.JARVIS_BRAIN_PORT,
    8181,
    1,
    65_535,
    "JARVIS_BRAIN_PORT",
  );
  const auditDirectory = optional(
    env,
    "JARVIS_AUDIT_DIR",
    "./logs/jarvis-audit",
  );
  if (auditDirectory.includes("\0") || auditDirectory.trim().length === 0) {
    throw new Error("JARVIS_AUDIT_DIR is invalid.");
  }

  const auditRetentionDays = boundedInteger(
    env.JARVIS_AUDIT_RETENTION_DAYS,
    DEFAULT_AUDIT_RETENTION_DAYS,
    1,
    365,
    "JARVIS_AUDIT_RETENTION_DAYS",
  );
  const auditMaxTotalBytes = boundedInteger(
    env.JARVIS_AUDIT_MAX_TOTAL_BYTES,
    DEFAULT_AUDIT_MAX_TOTAL_BYTES,
    1_048_576,
    10 * 1024 * 1024 * 1024,
    "JARVIS_AUDIT_MAX_TOTAL_BYTES",
  );
  const auditMaxFileBytes = boundedInteger(
    env.JARVIS_AUDIT_MAX_FILE_BYTES,
    DEFAULT_AUDIT_MAX_FILE_BYTES,
    262_144,
    1024 * 1024 * 1024,
    "JARVIS_AUDIT_MAX_FILE_BYTES",
  );
  if (auditMaxTotalBytes < auditMaxFileBytes * 2) {
    throw new Error(
      "JARVIS_AUDIT_MAX_TOTAL_BYTES must be at least twice JARVIS_AUDIT_MAX_FILE_BYTES.",
    );
  }

  const auditMaxQueue = boundedInteger(
    env.JARVIS_AUDIT_MAX_QUEUE,
    DEFAULT_AUDIT_MAX_QUEUE,
    1,
    100_000,
    "JARVIS_AUDIT_MAX_QUEUE",
  );

  return {
    openaiApiKey,
    typesafeApiKey,
    sharedSecret,
    bindHost,
    bindPort,
    auditDirectory,
    auditRetentionDays,
    auditMaxTotalBytes,
    auditMaxFileBytes,
    auditMaxQueue,
    policy: FIXED_V01_POLICY,
  };
}

export function publicConfigSummary(config: OpsConfig) {
  return {
    bindHost: config.bindHost,
    bindPort: config.bindPort,
    auditDirectory: config.auditDirectory,
    auditRetentionDays: config.auditRetentionDays,
    auditMaxTotalBytes: config.auditMaxTotalBytes,
    auditMaxFileBytes: config.auditMaxFileBytes,
    auditMaxQueue: config.auditMaxQueue,
    openaiConfigured: config.openaiApiKey.length > 0,
    typesafeConfigured: config.typesafeApiKey.length > 0,
    sharedSecretConfigured: config.sharedSecret.length > 0,
    policy: config.policy,
  } as const;
}

function requiredSecret(
  env: Readonly<Record<string, string | undefined>>,
  name: string,
): string {
  const value = env[name]?.trim();
  if (value === undefined || value.length < 16) {
    throw new Error(name + " must be configured with at least 16 non-space characters.");
  }
  if (/\s/.test(value)) {
    throw new Error(name + " must not contain whitespace.");
  }
  if (
    /^(change[_-]?me|replace([_-].*)?|your[_-]?.*key.*|example.*|test.*)$/i.test(
      value,
    )
  ) {
    throw new Error(name + " contains a sample placeholder.");
  }
  return value;
}

function optional(
  env: Readonly<Record<string, string | undefined>>,
  name: string,
  fallback: string,
): string {
  const value = env[name]?.trim();
  return value === undefined || value.length === 0 ? fallback : value;
}

function boundedInteger(
  raw: string | undefined,
  fallback: number,
  min: number,
  max: number,
  name: string,
): number {
  if (raw === undefined || raw.trim().length === 0) {
    return fallback;
  }
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    throw new Error(name + " is outside the supported integer range.");
  }
  return value;
}
