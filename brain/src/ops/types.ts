import type { AuditEvent, AuditPort } from "../core/ports.js";

export interface AuditFileInfo {
  readonly name: string;
  readonly size: number;
  readonly mtimeMs: number;
}

export interface AuditFileSystem {
  ensureDirectory(path: string): Promise<void>;
  list(path: string): Promise<readonly AuditFileInfo[]>;
  append(path: string, data: string): Promise<void>;
  remove(path: string): Promise<void>;
}

export interface JsonlAuditOptions {
  readonly directory: string;
  readonly retentionDays?: number;
  readonly maxTotalBytes?: number;
  readonly maxFileBytes?: number;
  readonly maxQueue?: number;
  readonly nowMs?: () => number;
  readonly fileSystem?: AuditFileSystem;
}

export interface AuditHealthSnapshot {
  readonly status: "HEALTHY" | "DEGRADED" | "UNHEALTHY";
  readonly writable: boolean;
  readonly closed: boolean;
  readonly queueDepth: number;
  readonly maxQueue: number;
  readonly rejectedRecords: number;
  readonly lastSuccessfulWriteAt: string | null;
  readonly lastErrorAt: string | null;
  readonly lastErrorCode: string | null;
  readonly totalBytes: number;
  readonly fileCount: number;
  readonly retentionDays: number;
  readonly maxTotalBytes: number;
  readonly maxFileBytes: number;
}

export interface OpsPolicy {
  readonly opOnly: true;
  readonly replyRequesterOnly: true;
  readonly warningEnabled: false;
  readonly rollbackEnabled: false;
}

export interface OpsConfig {
  readonly openaiApiKey: string;
  readonly typesafeApiKey: string;
  readonly sharedSecret: string;
  readonly bindHost: string;
  readonly bindPort: number;
  readonly auditDirectory: string;
  readonly auditRetentionDays: number;
  readonly auditMaxTotalBytes: number;
  readonly auditMaxFileBytes: number;
  readonly auditMaxQueue: number;
  readonly policy: OpsPolicy;
}

export interface OpsHealthSnapshot {
  readonly status: "HEALTHY" | "DEGRADED" | "UNHEALTHY";
  readonly startedAt: string;
  readonly providers: {
    readonly openaiConfigured: boolean;
    readonly typesafeConfigured: boolean;
  };
  readonly policy: OpsPolicy;
  readonly audit: AuditHealthSnapshot;
}

export interface ManagedAuditPort extends AuditPort {
  record(event: AuditEvent): Promise<boolean>;
  health(): AuditHealthSnapshot;
  close(): Promise<void>;
}
