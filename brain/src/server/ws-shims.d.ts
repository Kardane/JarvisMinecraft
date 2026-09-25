declare module "ws" {
  export interface RawData {
    toString(encoding?: string): string;
  }

  export interface UpgradeRequest {
    readonly url?: string;
    readonly headers: Readonly<Record<string, string | readonly string[] | undefined>>;
  }

  export interface VerifyClientInfo {
    readonly req: UpgradeRequest;
  }

  export type VerifyClientCallback = (
    accepted: boolean,
    code?: number,
    message?: string,
    headers?: Readonly<Record<string, string>>,
  ) => void;

  export class WebSocket {
    static readonly OPEN: number;
    readonly readyState: number;

    send(data: string, callback?: (error?: Error | null) => void): void;
    close(code?: number, reason?: string): void;
    terminate(): void;

    on(
      event: "message",
      listener: (data: RawData, isBinary: boolean) => void,
    ): this;
    on(
      event: "close",
      listener: (code: number, reason: RawData) => void,
    ): this;
    on(event: "error", listener: (error: Error) => void): this;
  }

  export interface WebSocketServerOptions {
    readonly host?: string;
    readonly port?: number;
    readonly path?: string;
    readonly maxPayload?: number;
    readonly perMessageDeflate?: boolean;
    readonly verifyClient?: (
      info: VerifyClientInfo,
      done: VerifyClientCallback,
    ) => void;
  }

  export class WebSocketServer {
    constructor(options: WebSocketServerOptions);

    on(event: "connection", listener: (socket: WebSocket) => void): this;
    once(event: "listening", listener: () => void): this;
    once(event: "error", listener: (error: Error) => void): this;
    off(event: "listening", listener: () => void): this;
    off(event: "error", listener: (error: Error) => void): this;
    close(callback: (error?: Error | null) => void): void;
  }
}
