import type { BrainCore } from "../core/brain-core.js";
import type { JsonValue } from "../core/types.js";

export interface BrainServerEvent {
  readonly at: string;
  readonly kind: string;
  readonly serverId: string | null;
  readonly connectionId: string | null;
  readonly requestId: string | null;
  readonly details: Readonly<Record<string, JsonValue>>;
}

export interface BrainServerObserver {
  onEvent(event: BrainServerEvent): void;
}

export interface BrainWebSocketServerOptions {
  readonly core: BrainCore;
  readonly host: string;
  readonly port: number;
  readonly sharedSecret: string;
  readonly path?: string;
  readonly brainVersion?: string;
  readonly handshakeTimeoutMs?: number;
  readonly observer?: BrainServerObserver;
  readonly nowMs?: () => number;
}

export interface BrainServerHealth {
  readonly status: "STOPPED" | "LISTENING" | "DEGRADED";
  readonly host: string;
  readonly port: number;
  readonly path: string;
  readonly activeServers: readonly string[];
  readonly connectionCount: number;
}
