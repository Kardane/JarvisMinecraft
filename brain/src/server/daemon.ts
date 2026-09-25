import { JarvisAiModel, OpenAiLunaPort, TypeSafeJevClassifier } from "../ai/index.js";
import { BrainCore } from "../core/index.js";
import { OpsRuntime } from "../ops/index.js";
import { BrainWebSocketServer } from "./brain-websocket-server.js";
import type { BrainServerHealth } from "./types.js";

export interface ProductionBrainHealth {
  readonly status: "HEALTHY" | "DEGRADED" | "UNHEALTHY";
  readonly server: BrainServerHealth;
  readonly ops: ReturnType<OpsRuntime["health"]>;
}

export class ProductionBrainDaemon {
  readonly #ops: OpsRuntime;
  readonly #server: BrainWebSocketServer;
  #started = false;

  private constructor(
    ops: OpsRuntime,
    server: BrainWebSocketServer,
  ) {
    this.#ops = ops;
    this.#server = server;
  }

  static fromEnvironment(
    env: Readonly<Record<string, string | undefined>>,
  ): ProductionBrainDaemon {
    const ops = OpsRuntime.fromEnvironment(env);
    const classifier = new TypeSafeJevClassifier(ops.config.typesafeApiKey);
    const luna = new OpenAiLunaPort(ops.config.openaiApiKey);
    const model = new JarvisAiModel({ classifier, luna });
    const core = new BrainCore({
      model,
      audit: ops.audit,
    });
    const server = new BrainWebSocketServer({
      core,
      host: ops.config.bindHost,
      port: ops.config.bindPort,
      sharedSecret: ops.config.sharedSecret,
      path: "/ws",
      brainVersion: "0.1.0-dev",
    });
    return new ProductionBrainDaemon(ops, server);
  }

  async start(): Promise<void> {
    if (this.#started) {
      return;
    }
    try {
      await this.#server.start();
      this.#started = true;
    } catch (error) {
      await this.#ops.close().catch(() => {});
      throw error;
    }
  }

  async stop(): Promise<void> {
    if (this.#started) {
      this.#started = false;
      await this.#server.stop();
    }
    await this.#ops.close();
  }

  health(): ProductionBrainHealth {
    const server = this.#server.health();
    const ops = this.#ops.health();
    let status: ProductionBrainHealth["status"] = ops.status;
    if (server.status === "STOPPED") {
      status = "UNHEALTHY";
    } else if (server.status === "DEGRADED" && status === "HEALTHY") {
      status = "DEGRADED";
    }
    return { status, server, ops };
  }
}
