import { join } from "node:path";

import type { AuditEvent } from "../core/ports.js";
import { sanitizeAuditEvent } from "./masking.js";
import { nodeAuditFileSystem } from "./node-file-system.js";
import type {
  AuditFileInfo,
  AuditFileSystem,
  AuditHealthSnapshot,
  JsonlAuditOptions,
  ManagedAuditPort,
} from "./types.js";

const FILE_PATTERN = /^jarvis-audit-(\d{4}-\d{2}-\d{2})-(\d{4})\.jsonl$/;

interface PendingRecord {
  readonly event: AuditEvent;
  readonly resolve: (recorded: boolean) => void;
}

export class JsonlAuditSink implements ManagedAuditPort {
  readonly #directory: string;
  readonly #retentionDays: number;
  readonly #maxTotalBytes: number;
  readonly #maxFileBytes: number;
  readonly #maxQueue: number;
  readonly #nowMs: () => number;
  readonly #fs: AuditFileSystem;
  readonly #queue: PendingRecord[] = [];

  #drainPromise: Promise<void> | null = null;
  #closed = false;
  #writable = true;
  #rejectedRecords = 0;
  #lastSuccessfulWriteAt: string | null = null;
  #lastErrorAt: string | null = null;
  #lastErrorCode: string | null = null;
  #totalBytes = 0;
  #fileCount = 0;

  constructor(options: JsonlAuditOptions) {
    this.#directory = requiredDirectory(options.directory);
    this.#retentionDays = options.retentionDays ?? 7;
    this.#maxTotalBytes = options.maxTotalBytes ?? 100 * 1024 * 1024;
    this.#maxFileBytes = options.maxFileBytes ?? 8 * 1024 * 1024;
    this.#maxQueue = options.maxQueue ?? 512;
    this.#nowMs = options.nowMs ?? (() => Date.now());
    this.#fs = options.fileSystem ?? nodeAuditFileSystem;

    if (!Number.isInteger(this.#retentionDays) || this.#retentionDays < 1) {
      throw new Error("Audit retentionDays must be a positive integer.");
    }
    if (
      !Number.isSafeInteger(this.#maxFileBytes) ||
      this.#maxFileBytes < 1_024
    ) {
      throw new Error("Audit maxFileBytes must be at least 1024.");
    }
    if (
      !Number.isSafeInteger(this.#maxTotalBytes) ||
      this.#maxTotalBytes < this.#maxFileBytes * 2
    ) {
      throw new Error("Audit maxTotalBytes must be at least twice maxFileBytes.");
    }
    if (!Number.isInteger(this.#maxQueue) || this.#maxQueue < 1) {
      throw new Error("Audit maxQueue must be a positive integer.");
    }
  }

  record(event: AuditEvent): Promise<boolean> {
    if (this.#closed) {
      this.#reject("AUDIT_CLOSED", false);
      return Promise.resolve(false);
    }
    if (this.#queue.length >= this.#maxQueue) {
      this.#reject("AUDIT_QUEUE_FULL", true);
      return Promise.resolve(false);
    }

    const result = new Promise<boolean>((resolve) => {
      this.#queue.push({ event, resolve });
    });
    this.#kickDrain();
    return result;
  }

  health(): AuditHealthSnapshot {
    let status: AuditHealthSnapshot["status"] = "HEALTHY";
    if (!this.#writable) {
      status = "UNHEALTHY";
    } else if (
      this.#closed ||
      this.#queue.length >= Math.max(1, Math.floor(this.#maxQueue * 0.8)) ||
      this.#lastErrorCode !== null
    ) {
      status = "DEGRADED";
    }

    return {
      status,
      writable: this.#writable,
      closed: this.#closed,
      queueDepth: this.#queue.length,
      maxQueue: this.#maxQueue,
      rejectedRecords: this.#rejectedRecords,
      lastSuccessfulWriteAt: this.#lastSuccessfulWriteAt,
      lastErrorAt: this.#lastErrorAt,
      lastErrorCode: this.#lastErrorCode,
      totalBytes: this.#totalBytes,
      fileCount: this.#fileCount,
      retentionDays: this.#retentionDays,
      maxTotalBytes: this.#maxTotalBytes,
      maxFileBytes: this.#maxFileBytes,
    };
  }

  async close(): Promise<void> {
    this.#closed = true;
    const active = this.#drainPromise;
    if (active !== null) {
      await active;
    }
  }

  #kickDrain(): void {
    if (this.#drainPromise !== null) {
      return;
    }
    this.#drainPromise = this.#drain().finally(() => {
      this.#drainPromise = null;
      if (this.#queue.length > 0) {
        this.#kickDrain();
      }
    });
  }

  async #drain(): Promise<void> {
    while (this.#queue.length > 0) {
      const pending = this.#queue.shift();
      if (pending === undefined) {
        continue;
      }

      try {
        await this.#write(pending.event);
        pending.resolve(true);
      } catch {
        this.#reject("AUDIT_IO_ERROR", false);
        pending.resolve(false);
      }
    }
  }

  async #write(event: AuditEvent): Promise<void> {
    const sanitized = sanitizeAuditEvent(event);
    const line = JSON.stringify(sanitized) + "\n";
    const lineBytes = new TextEncoder().encode(line).byteLength;
    if (lineBytes > this.#maxFileBytes) {
      this.#reject("AUDIT_RECORD_TOO_LARGE", true);
      throw new Error("Audit record exceeds maxFileBytes.");
    }

    await this.#fs.ensureDirectory(this.#directory);

    const now = this.#nowMs();
    let files = await this.#auditFiles();
    files = await this.#deleteExpired(files, now);

    const target = this.#chooseTarget(files, lineBytes, now);
    const protectedName = target.existing ? target.name : null;
    files = await this.#enforceTotalLimit(
      files,
      lineBytes,
      protectedName,
    );

    const totalBefore = files.reduce((sum, file) => sum + file.size, 0);
    if (totalBefore + lineBytes > this.#maxTotalBytes) {
      this.#reject("AUDIT_TOTAL_LIMIT", true);
      throw new Error("Audit total-byte limit cannot accommodate the record.");
    }

    await this.#fs.append(join(this.#directory, target.name), line);

    const targetExisted = files.some((file) => file.name === target.name);
    this.#totalBytes = totalBefore + lineBytes;
    this.#fileCount = files.length + (targetExisted ? 0 : 1);
    this.#writable = true;
    this.#lastSuccessfulWriteAt = new Date(now).toISOString();
    this.#lastErrorAt = null;
    this.#lastErrorCode = null;
  }

  async #auditFiles(): Promise<AuditFileInfo[]> {
    const listed = await this.#fs.list(this.#directory);
    return listed
      .filter((file) => FILE_PATTERN.test(file.name))
      .map((file) => ({ ...file }))
      .sort(compareFiles);
  }

  async #deleteExpired(
    files: AuditFileInfo[],
    now: number,
  ): Promise<AuditFileInfo[]> {
    const cutoff = now - this.#retentionDays * 24 * 60 * 60 * 1_000;
    const kept: AuditFileInfo[] = [];
    for (const file of files) {
      if (file.mtimeMs < cutoff) {
        await this.#fs.remove(join(this.#directory, file.name));
      } else {
        kept.push(file);
      }
    }
    return kept;
  }

  #chooseTarget(
    files: readonly AuditFileInfo[],
    incomingBytes: number,
    now: number,
  ): { readonly name: string; readonly existing: boolean } {
    const day = new Date(now).toISOString().slice(0, 10);
    const sameDay = files
      .map((file) => ({
        file,
        match: FILE_PATTERN.exec(file.name),
      }))
      .filter(
        (item): item is { file: AuditFileInfo; match: RegExpExecArray } =>
          item.match !== null && item.match[1] === day,
      )
      .sort((a, b) => Number(a.match[2]) - Number(b.match[2]));

    const last = sameDay.at(-1);
    if (
      last !== undefined &&
      last.file.size + incomingBytes <= this.#maxFileBytes
    ) {
      return { name: last.file.name, existing: true };
    }

    const nextSequence =
      last === undefined ? 1 : Number(last.match[2]) + 1;
    return {
      name:
        "jarvis-audit-" +
        day +
        "-" +
        String(nextSequence).padStart(4, "0") +
        ".jsonl",
      existing: false,
    };
  }

  async #enforceTotalLimit(
    files: AuditFileInfo[],
    incomingBytes: number,
    protectedName: string | null,
  ): Promise<AuditFileInfo[]> {
    const kept = [...files];
    let total = kept.reduce((sum, file) => sum + file.size, 0);

    while (total + incomingBytes > this.#maxTotalBytes) {
      const index = kept.findIndex(
        (file) => file.name !== protectedName,
      );
      if (index < 0) {
        break;
      }
      const [oldest] = kept.splice(index, 1);
      if (oldest === undefined) {
        break;
      }
      await this.#fs.remove(join(this.#directory, oldest.name));
      total -= oldest.size;
    }

    return kept;
  }

  #reject(code: string, writable: boolean): void {
    this.#rejectedRecords += 1;
    this.#lastErrorAt = new Date(this.#nowMs()).toISOString();
    this.#lastErrorCode = code;
    this.#writable = writable;
  }
}

function requiredDirectory(value: string): string {
  const trimmed = value.trim();
  if (trimmed.length === 0 || trimmed.includes("\0")) {
    throw new Error("Audit directory is invalid.");
  }
  return trimmed;
}

function compareFiles(a: AuditFileInfo, b: AuditFileInfo): number {
  if (a.mtimeMs !== b.mtimeMs) {
    return a.mtimeMs - b.mtimeMs;
  }
  return a.name.localeCompare(b.name);
}
