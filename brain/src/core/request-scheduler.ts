import { CoreError } from "./errors.js";

interface QueueItem {
  readonly serverId: string;
  readonly sessionKey: string;
  readonly run: () => Promise<unknown>;
  readonly resolve: (value: unknown) => void;
  readonly reject: (error: unknown) => void;
}

export interface SchedulerSnapshot {
  readonly queuedTotal: number;
  readonly activeSessions: number;
  readonly activeByServer: Readonly<Record<string, number>>;
}

export class RequestScheduler {
  static readonly MAX_ACTIVE_PER_SERVER = 4;
  static readonly MAX_QUEUED_PER_SESSION = 2;
  static readonly MAX_QUEUED_TOTAL = 16;

  readonly #activeSessions = new Set<string>();
  readonly #activeByServer = new Map<string, number>();
  readonly #queuedBySession = new Map<string, number>();
  readonly #queue: QueueItem[] = [];

  submit<T>(
    serverId: string,
    sessionKey: string,
    job: () => Promise<T>,
  ): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const item: QueueItem = {
        serverId,
        sessionKey,
        run: async () => await job(),
        resolve: (value) => resolve(value as T),
        reject: (error) => reject(error),
      };

      if (this.canStartImmediately(serverId, sessionKey)) {
        this.start(item);
        return;
      }

      const sessionQueued = this.#queuedBySession.get(sessionKey) ?? 0;
      if (sessionQueued >= RequestScheduler.MAX_QUEUED_PER_SESSION) {
        reject(new CoreError("BUSY", "Session queue is full.", true));
        return;
      }
      if (this.#queue.length >= RequestScheduler.MAX_QUEUED_TOTAL) {
        reject(new CoreError("BUSY", "Global Brain queue is full.", true));
        return;
      }

      this.#queue.push(item);
      this.#queuedBySession.set(sessionKey, sessionQueued + 1);
    });
  }

  cancelServer(serverId: string): void {
    for (let index = this.#queue.length - 1; index >= 0; index -= 1) {
      const item = this.#queue[index];
      if (item === undefined || item.serverId !== serverId) {
        continue;
      }
      this.#queue.splice(index, 1);
      this.decrementQueued(item.sessionKey);
      item.reject(
        new CoreError(
          "CANCELLED",
          "Queued request was cancelled because the Adapter reconnected.",
        ),
      );
    }
  }

  snapshot(): SchedulerSnapshot {
    return {
      queuedTotal: this.#queue.length,
      activeSessions: this.#activeSessions.size,
      activeByServer: Object.fromEntries(this.#activeByServer),
    };
  }

  private canStartImmediately(serverId: string, sessionKey: string): boolean {
    if (this.#activeSessions.has(sessionKey)) {
      return false;
    }
    if ((this.#queuedBySession.get(sessionKey) ?? 0) > 0) {
      return false;
    }
    return (
      (this.#activeByServer.get(serverId) ?? 0) <
      RequestScheduler.MAX_ACTIVE_PER_SERVER
    );
  }

  private start(item: QueueItem): void {
    this.#activeSessions.add(item.sessionKey);
    this.#activeByServer.set(
      item.serverId,
      (this.#activeByServer.get(item.serverId) ?? 0) + 1,
    );

    Promise.resolve()
      .then(item.run)
      .then(item.resolve, item.reject)
      .finally(() => {
        this.#activeSessions.delete(item.sessionKey);
        const active = (this.#activeByServer.get(item.serverId) ?? 1) - 1;
        if (active <= 0) {
          this.#activeByServer.delete(item.serverId);
        } else {
          this.#activeByServer.set(item.serverId, active);
        }
        this.dispatch();
      });
  }

  private dispatch(): void {
    while (true) {
      const index = this.#queue.findIndex(
        (item) =>
          !this.#activeSessions.has(item.sessionKey) &&
          (this.#activeByServer.get(item.serverId) ?? 0) <
            RequestScheduler.MAX_ACTIVE_PER_SERVER,
      );
      if (index < 0) {
        return;
      }

      const [item] = this.#queue.splice(index, 1);
      if (item === undefined) {
        return;
      }
      this.decrementQueued(item.sessionKey);
      this.start(item);
    }
  }

  private decrementQueued(sessionKey: string): void {
    const count = this.#queuedBySession.get(sessionKey) ?? 0;
    if (count <= 1) {
      this.#queuedBySession.delete(sessionKey);
    } else {
      this.#queuedBySession.set(sessionKey, count - 1);
    }
  }
}
