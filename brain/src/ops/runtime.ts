import type { AuditFileSystem, OpsConfig, OpsHealthSnapshot } from "./types.js";
import { JsonlAuditSink } from "./audit-jsonl.js";
import { loadOpsConfig } from "./config.js";

export interface OpsRuntimeOptions {
  readonly fileSystem?: AuditFileSystem;
  readonly nowMs?: () => number;
}

export class OpsRuntime {
  readonly config: OpsConfig;
  readonly audit: JsonlAuditSink;
  readonly #startedAt: string;

  constructor(
    config: OpsConfig,
    options: OpsRuntimeOptions = {},
  ) {
    this.config = config;
    const nowMs = options.nowMs ?? (() => Date.now());
    this.#startedAt = new Date(nowMs()).toISOString();
    this.audit = new JsonlAuditSink({
      directory: config.auditDirectory,
      retentionDays: config.auditRetentionDays,
      maxTotalBytes: config.auditMaxTotalBytes,
      maxFileBytes: config.auditMaxFileBytes,
      maxQueue: config.auditMaxQueue,
      nowMs,
      ...(options.fileSystem === undefined
        ? {}
        : { fileSystem: options.fileSystem }),
    });
  }

  static fromEnvironment(
    env: Readonly<Record<string, string | undefined>>,
    options: OpsRuntimeOptions = {},
  ): OpsRuntime {
    return new OpsRuntime(loadOpsConfig(env), options);
  }

  health(): OpsHealthSnapshot {
    const audit = this.audit.health();
    return {
      status: audit.status,
      startedAt: this.#startedAt,
      providers: {
        openaiConfigured: this.config.openaiApiKey.length > 0,
        typesafeConfigured: this.config.typesafeApiKey.length > 0,
      },
      policy: this.config.policy,
      audit,
    };
  }

  async close(): Promise<void> {
    await this.audit.close();
  }
}
