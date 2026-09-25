import { ProductionBrainDaemon } from "./daemon.js";

let daemon: ProductionBrainDaemon | null = null;
let stopping = false;

async function shutdown(signal: "SIGINT" | "SIGTERM"): Promise<void> {
  if (stopping) {
    return;
  }
  stopping = true;
  console.info("[JARVIS Brain] shutdown requested: " + signal);
  try {
    await daemon?.stop();
  } catch (error) {
    console.error("[JARVIS Brain] shutdown failure: " + safeError(error));
    process.exitCode = 1;
  }
}

try {
  daemon = ProductionBrainDaemon.fromEnvironment(process.env);
  await daemon.start();
  const health = daemon.health();
  console.info(
    "[JARVIS Brain] listening on ws://" +
      health.server.host +
      ":" +
      health.server.port +
      health.server.path +
      " with provider keys configured and audit=" +
      health.ops.audit.status,
  );

  process.on("SIGINT", () => {
    void shutdown("SIGINT");
  });
  process.on("SIGTERM", () => {
    void shutdown("SIGTERM");
  });
} catch (error) {
  console.error("[JARVIS Brain] startup failed: " + safeError(error));
  process.exitCode = 1;
  if (daemon !== null) {
    await daemon.stop().catch(() => {});
  }
}

function safeError(error: unknown): string {
  return (error instanceof Error ? error.message : String(error)).slice(0, 512);
}
