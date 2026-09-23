import { CoreError } from "./errors.js";
import type {
  ActorBinding,
  ChatMessageEnvelope,
  ConversationEntry,
} from "./types.js";

interface SessionRecord {
  readonly key: string;
  readonly serverId: string;
  readonly requesterUuid: string;
  readonly sessionId: string;
  requesterName: string;
  expiresAtMs: number;
  readonly history: ConversationEntry[];
}

export interface AcceptedSession {
  readonly key: string;
  readonly binding: ActorBinding;
}

export class SessionStore {
  static readonly DEFAULT_TTL_MS = 120_000;
  static readonly MAX_HISTORY_ENTRIES = 32;

  readonly #ttlMs: number;
  readonly #sessions = new Map<string, SessionRecord>();

  constructor(ttlMs = SessionStore.DEFAULT_TTL_MS) {
    if (!Number.isFinite(ttlMs) || ttlMs < 1) {
      throw new Error("Session TTL must be positive.");
    }
    this.#ttlMs = ttlMs;
  }

  accept(message: ChatMessageEnvelope, nowMs: number): AcceptedSession {
    this.expire(nowMs);
    const key = sessionKey(
      message.serverId,
      message.requesterUuid,
      message.sessionId,
    );
    let session = this.#sessions.get(key);

    if (message.payload.mode === "DIRECT") {
      if (session === undefined) {
        session = {
          key,
          serverId: message.serverId,
          requesterUuid: message.requesterUuid,
          sessionId: message.sessionId,
          requesterName: message.payload.requesterName,
          expiresAtMs: nowMs + this.#ttlMs,
          history: [],
        };
        this.#sessions.set(key, session);
      } else {
        session.requesterName = message.payload.requesterName;
        session.expiresAtMs = nowMs + this.#ttlMs;
      }
    } else {
      if (session === undefined || session.expiresAtMs <= nowMs) {
        if (session !== undefined) {
          this.#sessions.delete(key);
        }
        throw new CoreError("CANCELLED", "Conversation session is not active.");
      }
      session.requesterName = message.payload.requesterName;
      session.expiresAtMs = nowMs + this.#ttlMs;
    }

    return {
      key,
      binding: {
        serverId: message.serverId,
        requesterUuid: message.requesterUuid,
        sessionId: message.sessionId,
        requestId: message.requestId,
      },
    };
  }

  isActive(key: string, nowMs: number): boolean {
    const session = this.#sessions.get(key);
    if (session === undefined) {
      return false;
    }
    if (session.expiresAtMs <= nowMs) {
      this.#sessions.delete(key);
      return false;
    }
    return true;
  }

  history(key: string, nowMs: number): readonly ConversationEntry[] {
    const session = this.requireActive(key, nowMs);
    return session.history.slice();
  }

  append(key: string, entry: ConversationEntry, nowMs: number): void {
    const session = this.requireActive(key, nowMs);
    session.history.push(entry);
    if (session.history.length > SessionStore.MAX_HISTORY_ENTRIES) {
      session.history.splice(
        0,
        session.history.length - SessionStore.MAX_HISTORY_ENTRIES,
      );
    }
  }

  end(key: string): void {
    this.#sessions.delete(key);
  }

  invalidateActor(serverId: string, requesterUuid: string): void {
    for (const [key, session] of this.#sessions) {
      if (
        session.serverId === serverId &&
        session.requesterUuid === requesterUuid
      ) {
        this.#sessions.delete(key);
      }
    }
  }

  invalidateServer(serverId: string): void {
    for (const [key, session] of this.#sessions) {
      if (session.serverId === serverId) {
        this.#sessions.delete(key);
      }
    }
  }

  expire(nowMs: number): void {
    for (const [key, session] of this.#sessions) {
      if (session.expiresAtMs <= nowMs) {
        this.#sessions.delete(key);
      }
    }
  }

  private requireActive(key: string, nowMs: number): SessionRecord {
    const session = this.#sessions.get(key);
    if (session === undefined || session.expiresAtMs <= nowMs) {
      if (session !== undefined) {
        this.#sessions.delete(key);
      }
      throw new CoreError("CANCELLED", "Conversation session is not active.");
    }
    return session;
  }
}

export function sessionKey(
  serverId: string,
  requesterUuid: string,
  sessionId: string,
): string {
  return serverId + "\u0000" + requesterUuid + "\u0000" + sessionId;
}
