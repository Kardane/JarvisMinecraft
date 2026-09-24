import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import {
  access,
  copyFile,
  cp,
  mkdir,
  readFile,
  readdir,
  rm,
  writeFile,
} from "node:fs/promises";
import { constants as fsConstants } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import mineflayer from "mineflayer";

import { AcceptanceGateway } from "./gateway.mjs";

const PLATFORM = process.argv[2];
if (!["paper", "fabric", "neoforge"].includes(PLATFORM)) {
  throw new Error("Usage: node run-platform.mjs <paper|fabric|neoforge>");
}

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "../../..");
const RUN_ROOT = join(ROOT, ".t10", PLATFORM);
const OUT_DIR = join(ROOT, "tests", "acceptance", "out");
const PORT = 25565;
const BRAIN_PORT = 8181;
const SECRET = "correct-horse-battery-staple";
const SERVER_ID = "t10-" + PLATFORM;
const USER_AGENT =
  "JarvisMinecraft-T10/0.1 (https://github.com/Kardane/JarvisMinecraft)";

const evidence = {
  platform: PLATFORM,
  serverId: SERVER_ID,
  startedAt: new Date().toISOString(),
  passed: false,
  checks: [],
  clients: {},
  gateway: null,
  server: {
    ready: false,
    jarvisEnabled: false,
    logTail: [],
  },
};

let gateway;
let serverProcess;
let bots = [];

try {
  await mkdir(OUT_DIR, { recursive: true });
  await rm(RUN_ROOT, { recursive: true, force: true });
  await mkdir(RUN_ROOT, { recursive: true });

  const adapterJar = await findAdapterJar(PLATFORM);
  await prepareServer(PLATFORM, adapterJar);
  await writeServerFiles();

  gateway = new AcceptanceGateway({ port: BRAIN_PORT, secret: SECRET });
  await gateway.start();

  serverProcess = await startServer();
  await waitForServerReady(serverProcess, 180_000);
  evidence.server.ready = true;

  await gateway.waitForActive(SERVER_ID, 30_000);
  evidence.server.jarvisEnabled = true;
  pass("adapter-handshake", true);

  bots = await Promise.all([
    createBot("AdminA"),
    createBot("NonOp"),
    createBot("OtherOp"),
  ]);
  await sleep(1_000);

  await scenarioA01();
  await scenarioA03();
  await scenarioA05();
  await scenarioA02();
  await scenarioA06A12();
  await scenarioA07();

  evidence.gateway = gateway.snapshot();
  evidence.passed = true;
  evidence.finishedAt = new Date().toISOString();
  await writeEvidence();

  console.log(
    "T10_LIVE_RESULT " +
      JSON.stringify({
        platform: PLATFORM,
        passed: true,
        checks: evidence.checks.length,
        chats: evidence.gateway.records.filter((item) => item.kind === "inbound.chat.message").length,
        tools: evidence.gateway.records.filter((item) => item.kind === "outbound.tool.request").length,
      }),
  );
} catch (error) {
  evidence.error = error instanceof Error ? error.stack ?? error.message : String(error);
  evidence.gateway = gateway?.snapshot() ?? null;
  evidence.finishedAt = new Date().toISOString();
  await writeEvidence().catch(() => {});
  throw error;
} finally {
  for (const bot of bots) {
    try {
      bot.quit("T10 complete");
    } catch {}
  }
  if (serverProcess) {
    await stopServer(serverProcess);
  }
  if (gateway) {
    await gateway.stop().catch(() => {});
  }
}

async function scenarioA01() {
  const [admin, nonOp, otherOp] = bots;
  clearMessages();

  const beforeNonOp = gateway.chatMessages(SERVER_ID).length;
  nonOp.chat("자비스 비OP 테스트");
  await sleep(1_000);
  const afterNonOp = gateway.chatMessages(SERVER_ID).length;
  pass("A01-nonop-external-zero", afterNonOp === beforeNonOp, {
    before: beforeNonOp,
    after: afterNonOp,
  });

  clearMessages();
  admin.chat("자비스 서버 상태 어때?");
  const response = await waitForMessage(admin, "[JARVIS] SERVER_STATUS", 15_000);
  pass("A01-requester-received", response.includes("SERVER_STATUS"));
  await sleep(500);
  pass(
    "A01-other-players-did-not-receive-response",
    !messagesOf(nonOp).some((line) => line.includes("SERVER_STATUS")) &&
      !messagesOf(otherOp).some((line) => line.includes("SERVER_STATUS")),
    {
      nonOp: messagesOf(nonOp),
      otherOp: messagesOf(otherOp),
    },
  );

  const adminChats = gateway
    .chatMessages(SERVER_ID)
    .filter((record) => record.payload?.requesterName === "AdminA");
  pass(
    "A01-op-chat-reached-brain-once",
    adminChats.some((record) => record.payload?.text === "자비스 서버 상태 어때?"),
  );
}

async function scenarioA03() {
  const [admin, nonOp, otherOp] = bots;

  clearMessages();
  const beforeFollow = gateway.chatMessages(SERVER_ID).length;
  admin.chat("그럼 접속자는?");
  await waitForMessage(admin, "[JARVIS] ONLINE", 15_000);
  const after = gateway.chatMessages(SERVER_ID);
  const follow = after.at(-1);
  pass("A03-follow-up-forwarded", after.length === beforeFollow + 1);
  pass("A03-follow-up-mode", follow?.payload?.mode === "FOLLOW_UP", {
    mode: follow?.payload?.mode,
  });

  clearMessages();
  const beforeEscape = gateway.chatMessages(SERVER_ID).length;
  admin.chat("!공개탈출");
  await waitForAnyMessage([nonOp, otherOp], "공개탈출", 10_000);
  await sleep(300);
  pass(
    "A03-public-escape-not-sent-to-brain",
    gateway.chatMessages(SERVER_ID).length === beforeEscape,
  );

  clearMessages();
  const beforeEnd = gateway.chatMessages(SERVER_ID).length;
  admin.chat("대화 끝");
  await waitForMessage(admin, "[JARVIS] 대화를 종료했습니다.", 10_000);
  await sleep(300);
  pass(
    "A03-end-private",
    !messagesOf(nonOp).some((line) => line.includes("대화를 종료했습니다")) &&
      !messagesOf(otherOp).some((line) => line.includes("대화를 종료했습니다")),
  );
  pass(
    "A03-end-not-forwarded-to-brain",
    gateway.chatMessages(SERVER_ID).length === beforeEnd,
  );

  clearMessages();
  const beforeAfterEnd = gateway.chatMessages(SERVER_ID).length;
  admin.chat("종료 후 일반 채팅");
  await waitForAnyMessage([nonOp, otherOp], "종료 후 일반 채팅", 10_000);
  await sleep(300);
  pass(
    "A03-session-really-ended",
    gateway.chatMessages(SERVER_ID).length === beforeAfterEnd,
  );
}

async function scenarioA05() {
  const [admin, , otherOp] = bots;

  sendConsole("gamemode creative AdminA");
  sendConsole("gamemode creative OtherOp");
  sendConsole("tp AdminA 0 100 0");
  sendConsole("tp OtherOp 20 100 20");
  await sleep(1_500);

  clearMessages();
  admin.chat("자비스 OtherOp 어디 있어?");
  const locationLine = await waitForMessage(admin, "[JARVIS] LOCATION ", 15_000);
  const json = locationLine.slice(locationLine.indexOf("LOCATION ") + 9);
  const observed = JSON.parse(json);
  const targetPosition = otherOp.entity.position;
  const delta = distance(observed, targetPosition);
  pass("A05-location-matches-client", delta < 2.5, {
    observed,
    target: vector(targetPosition),
    delta,
  });

  clearMessages();
  admin.chat("나 OtherOp한테 보내줘");
  await waitForMessage(admin, "[JARVIS] TELEPORT_OK", 15_000);
  await waitUntil(
    () => admin.entity.position.distanceTo(otherOp.entity.position) < 3,
    10_000,
    "teleport position convergence",
  );
  pass("A05-self-teleport-completed", true, {
    admin: vector(admin.entity.position),
    target: vector(otherOp.entity.position),
  });

  const snapshot = gateway.snapshot();
  const statusResult = snapshot.records.find(
    (record) =>
      record.kind === "inbound.tool.result" &&
      record.payload?.tool === "get_server_status",
  );
  pass(
    "A05-metrics-have-unit-and-observedAt",
    typeof statusResult?.payload?.data?.tps?.unit === "string" &&
      typeof statusResult?.payload?.data?.tps?.observedAt === "string" &&
      Number.isFinite(Date.parse(statusResult.payload.data.tps.observedAt)),
    { statusResult: statusResult?.payload ?? null },
  );

  const teleportRequest = snapshot.records.find(
    (record) =>
      record.kind === "outbound.tool.request" &&
      record.tool === "teleport_staff",
  );
  pass(
    "A05-teleport-has-actionId",
    typeof teleportRequest?.actionId === "string" &&
      teleportRequest.actionId.length === 36,
    { actionId: teleportRequest?.actionId ?? null },
  );
}

async function scenarioA02() {
  const [admin, , otherOp] = bots;
  sendConsole("deop AdminA");
  await sleep(750);

  clearMessages();
  const before = gateway.chatMessages(SERVER_ID).length;
  admin.chat("자비스 deop 이후 서버 상태");
  await waitForAnyMessage([otherOp], "deop 이후 서버 상태", 10_000);
  await sleep(500);
  pass(
    "A02-deopped-chat-not-sent-to-brain",
    gateway.chatMessages(SERVER_ID).length === before,
  );

  sendConsole("op AdminA");
  await sleep(750);
  clearMessages();
  admin.chat("자비스 서버 상태 복구 확인");
  await waitForMessage(admin, "[JARVIS] SERVER_STATUS", 15_000);
  pass("A02-reop-can-start-new-session", true);
}

async function scenarioA06A12() {
  const [admin] = bots;
  clearMessages();
  const beforeTools = gateway
    .snapshot()
    .records.filter((record) => record.kind === "outbound.tool.request").length;

  admin.chat("자비스 __A06_UNREGISTERED__");
  await waitForMessage(admin, "[JARVIS] 현재 이 서버에서는 해당 작업을 사용할 수 없습니다.", 10_000);
  const afterRecords = gateway.snapshot().records;
  const badTools = afterRecords.filter(
    (record) =>
      record.kind === "outbound.tool.request" &&
      record.tool === "run_console_command",
  );
  pass("A06-unregistered-tool-execution-zero", badTools.length === 0);

  const active = afterRecords
    .filter((record) => record.kind === "adapter.active")
    .at(-1);
  const forbidden = ["lookup_area_history", "lookup_player_history", "get_regions_at_location", "get_region_info", "check_build_permission"];
  pass(
    "A12-provider-tools-not-exposed",
    forbidden.every((tool) => !active?.tools?.includes(tool)),
    { activeTools: active?.tools ?? [] },
  );

  const afterTools = afterRecords.filter(
    (record) => record.kind === "outbound.tool.request",
  ).length;
  pass("A06-no-hidden-command-path", afterTools === beforeTools);
}

async function scenarioA07() {
  const [admin] = bots;
  const beforeConnections = gateway
    .snapshot()
    .records.filter((record) => record.kind === "adapter.active").length;

  await gateway.restart();
  await gateway.waitForActive(SERVER_ID, 20_000);

  const afterConnections = gateway
    .snapshot()
    .records.filter((record) => record.kind === "adapter.active").length;
  pass("A07-adapter-reconnected", afterConnections > beforeConnections, {
    beforeConnections,
    afterConnections,
  });

  clearMessages();
  admin.chat("자비스 서버 상태 재연결 확인");
  await waitForMessage(admin, "[JARVIS] SERVER_STATUS", 15_000);
  pass("A07-server-still-responsive-after-brain-restart", true);
}

async function createBot(username) {
  const messages = [];
  const bot = mineflayer.createBot({
    host: "127.0.0.1",
    port: PORT,
    username,
    auth: "offline",
    version: "1.21.8",
    physicsEnabled: false,
    hideErrors: false,
  });
  bot.__t10Messages = messages;
  bot.on("messagestr", (message) => {
    messages.push(String(message));
  });
  bot.on("error", (error) => {
    console.error("[bot:" + username + "]", error.message);
  });
  bot.on("kicked", (reason) => {
    console.error("[bot:" + username + "] kicked", reason);
  });
  await onceWithTimeout(bot, "spawn", 30_000);
  evidence.clients[username] = {
    uuid: bot.player?.uuid ?? null,
    initialPosition: vector(bot.entity.position),
  };
  return bot;
}

function clearMessages() {
  for (const bot of bots) {
    bot.__t10Messages.length = 0;
  }
}

function messagesOf(bot) {
  return [...bot.__t10Messages];
}

async function waitForMessage(bot, needle, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const found = bot.__t10Messages.find((line) => line.includes(needle));
    if (found !== undefined) return found;
    await sleep(100);
  }
  throw new Error(
    "Timed out waiting for " + JSON.stringify(needle) + " on " + bot.username +
      "; messages=" + JSON.stringify(bot.__t10Messages),
  );
}

async function waitForAnyMessage(candidates, needle, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    for (const bot of candidates) {
      const found = bot.__t10Messages.find((line) => line.includes(needle));
      if (found !== undefined) return { bot, found };
    }
    await sleep(100);
  }
  throw new Error("Timed out waiting for public message " + needle);
}

async function prepareServer(platform, adapterJar) {
  if (platform === "paper") {
    await preparePaper(adapterJar);
  } else if (platform === "fabric") {
    await prepareFabric(adapterJar);
  } else {
    await prepareNeoForge(adapterJar);
  }
}

async function preparePaper(adapterJar) {
  const plugins = join(RUN_ROOT, "plugins");
  await mkdir(join(plugins, "JarvisMinecraft"), { recursive: true });
  await copyFile(adapterJar, join(plugins, "jarvisminecraft.jar"));
  await writeFile(
    join(plugins, "JarvisMinecraft", "config.yml"),
    [
      "server-id: " + SERVER_ID,
      "brain-url: \"ws://127.0.0.1:" + BRAIN_PORT + "/ws\"",
      "shared-secret: \"" + SECRET + "\"",
      "reconnect-delay-ticks: 20",
      "",
    ].join("\n"),
    "utf8",
  );

  const response = await fetch(
    "https://fill.papermc.io/v3/projects/paper/versions/1.21.8/builds",
    { headers: { "User-Agent": USER_AGENT } },
  );
  if (!response.ok) throw new Error("Paper build API HTTP " + response.status);
  const builds = await response.json();
  const chosen =
    builds.find((item) => item.channel === "STABLE") ??
    builds.find((item) => item.channel === "BETA") ??
    builds[0];
  const url = chosen?.downloads?.["server:default"]?.url;
  if (typeof url !== "string") throw new Error("No Paper 1.21.8 server download");
  await download(url, join(RUN_ROOT, "paper.jar"));
}

async function prepareFabric(adapterJar) {
  const mods = join(RUN_ROOT, "mods");
  await mkdir(mods, { recursive: true });
  await copyFile(adapterJar, join(mods, "jarvisminecraft.jar"));

  const fabricApiUrl =
    "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.133.4+1.21.8/" +
    "fabric-api-0.133.4+1.21.8.jar";
  await download(fabricApiUrl, join(mods, "fabric-api.jar"));

  const response = await fetch("https://meta.fabricmc.net/v2/versions/installer");
  if (!response.ok) throw new Error("Fabric installer API HTTP " + response.status);
  const installers = await response.json();
  const installer = installers.find((item) => item.stable)?.version ?? installers[0]?.version;
  if (typeof installer !== "string") throw new Error("No Fabric installer version");
  const url =
    "https://meta.fabricmc.net/v2/versions/loader/1.21.8/0.17.2/" +
    installer +
    "/server/jar";
  await download(url, join(RUN_ROOT, "fabric-server.jar"));
}

async function prepareNeoForge(adapterJar) {
  const mods = join(RUN_ROOT, "mods");
  await mkdir(mods, { recursive: true });
  const installer = join(RUN_ROOT, "neoforge-installer.jar");
  await download(
    "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.8.52/" +
      "neoforge-21.8.52-installer.jar",
    installer,
  );
  await runProcess(
    "java",
    ["-jar", installer, "--installServer"],
    RUN_ROOT,
    process.env,
    180_000,
  );
  await copyFile(adapterJar, join(mods, "jarvisminecraft.jar"));
  await writeFile(
    join(RUN_ROOT, "user_jvm_args.txt"),
    "-Xms512M\n-Xmx1024M\n",
    "utf8",
  );
}

async function writeServerFiles() {
  await writeFile(join(RUN_ROOT, "eula.txt"), "eula=true\n", "utf8");
  await writeFile(
    join(RUN_ROOT, "server.properties"),
    [
      "online-mode=false",
      "enforce-secure-profile=false",
      "server-ip=127.0.0.1",
      "server-port=" + PORT,
      "spawn-protection=0",
      "view-distance=3",
      "simulation-distance=3",
      "max-players=10",
      "white-list=false",
      "motd=JARVIS T10 " + PLATFORM,
      "enable-rcon=false",
      "",
    ].join("\n"),
    "utf8",
  );
  const operators = ["AdminA", "OtherOp"].map((name) => ({
    uuid: offlineUuid(name),
    name,
    level: 4,
    bypassesPlayerLimit: false,
  }));
  await writeFile(
    join(RUN_ROOT, "ops.json"),
    JSON.stringify(operators, null, 2) + "\n",
    "utf8",
  );
}

async function startServer() {
  const env = {
    ...process.env,
    JARVIS_SERVER_ID: SERVER_ID,
    JARVIS_BRAIN_URL: "ws://127.0.0.1:" + BRAIN_PORT + "/ws",
    JARVIS_SHARED_SECRET: SECRET,
    JARVIS_RECONNECT_DELAY_TICKS: "20",
  };

  let command;
  let args;
  if (PLATFORM === "paper") {
    command = "java";
    args = ["-Xms512M", "-Xmx1024M", "-jar", "paper.jar", "--nogui"];
  } else if (PLATFORM === "fabric") {
    command = "java";
    args = ["-Xms512M", "-Xmx1024M", "-jar", "fabric-server.jar", "nogui"];
  } else {
    command = "bash";
    args = ["run.sh", "nogui"];
  }

  const child = spawn(command, args, {
    cwd: RUN_ROOT,
    env,
    stdio: ["pipe", "pipe", "pipe"],
  });
  child.__lines = [];
  const capture = (chunk) => {
    const text = chunk.toString("utf8");
    process.stdout.write("[server:" + PLATFORM + "] " + text);
    for (const line of text.split(/\r?\n/)) {
      if (line.length > 0) child.__lines.push(line);
    }
    if (child.__lines.length > 500) {
      child.__lines.splice(0, child.__lines.length - 500);
    }
  };
  child.stdout.on("data", capture);
  child.stderr.on("data", capture);
  child.on("exit", (code, signal) => {
    child.__exit = { code, signal };
  });
  return child;
}

async function waitForServerReady(child, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (child.__exit) {
      throw new Error(
        "Server exited before ready: " + JSON.stringify(child.__exit) +
          "\n" + child.__lines.slice(-80).join("\n"),
      );
    }
    if (child.__lines.some((line) => /Done \(.+\)! For help/.test(line))) {
      evidence.server.logTail = child.__lines.slice(-80);
      return;
    }
    await sleep(250);
  }
  throw new Error(
    "Timed out waiting for server ready\n" + child.__lines.slice(-100).join("\n"),
  );
}

async function stopServer(child) {
  if (child.__exit) return;
  try {
    child.stdin.write("stop\n");
  } catch {}
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline && !child.__exit) {
    await sleep(200);
  }
  if (!child.__exit) {
    child.kill("SIGKILL");
  }
  evidence.server.logTail = child.__lines?.slice(-100) ?? [];
}

function sendConsole(command) {
  if (!serverProcess || serverProcess.__exit) {
    throw new Error("Server process is not running");
  }
  serverProcess.stdin.write(command + "\n");
}

async function findAdapterJar(platform) {
  const directory = join(ROOT, "minecraft", platform, "build", "libs");
  const preferred = join(directory, platform + "-0.1.0-SNAPSHOT.jar");
  try {
    await access(preferred, fsConstants.R_OK);
    return preferred;
  } catch {}

  const names = await readdir(directory);
  const candidates = names.filter(
    (name) =>
      name.endsWith(".jar") &&
      !name.includes("sources") &&
      !name.includes("dev") &&
      !name.includes("javadoc"),
  );
  if (candidates.length !== 1) {
    throw new Error(
      "Could not select " + platform + " Adapter JAR: " + JSON.stringify(candidates),
    );
  }
  return join(directory, candidates[0]);
}

async function download(url, target) {
  const response = await fetch(url, {
    headers: {
      "User-Agent": USER_AGENT,
    },
    redirect: "follow",
  });
  if (!response.ok) {
    throw new Error("Download failed " + response.status + " " + url);
  }
  const bytes = new Uint8Array(await response.arrayBuffer());
  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, bytes);
}

async function runProcess(command, args, cwd, env, timeoutMs) {
  const child = spawn(command, args, {
    cwd,
    env,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let output = "";
  child.stdout.on("data", (chunk) => {
    output += chunk.toString("utf8");
  });
  child.stderr.on("data", (chunk) => {
    output += chunk.toString("utf8");
  });
  const result = await Promise.race([
    new Promise((resolve) =>
      child.on("exit", (code, signal) => resolve({ code, signal })),
    ),
    sleep(timeoutMs).then(() => ({ timeout: true })),
  ]);
  if (result.timeout) {
    child.kill("SIGKILL");
    throw new Error(command + " timed out\n" + output.slice(-10_000));
  }
  if (result.code !== 0) {
    throw new Error(
      command + " failed code=" + result.code + "\n" + output.slice(-10_000),
    );
  }
  return output;
}

function offlineUuid(name) {
  const bytes = createHash("md5")
    .update("OfflinePlayer:" + name, "utf8")
    .digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return (
    hex.slice(0, 8) +
    "-" +
    hex.slice(8, 12) +
    "-" +
    hex.slice(12, 16) +
    "-" +
    hex.slice(16, 20) +
    "-" +
    hex.slice(20)
  );
}

function onceWithTimeout(emitter, event, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      cleanup();
      reject(new Error("Timed out waiting for bot event " + event));
    }, timeoutMs);
    const onEvent = (...args) => {
      cleanup();
      resolve(args);
    };
    const onError = (error) => {
      cleanup();
      reject(error);
    };
    const cleanup = () => {
      clearTimeout(timeout);
      emitter.off(event, onEvent);
      emitter.off("error", onError);
    };
    emitter.once(event, onEvent);
    emitter.once("error", onError);
  });
}

async function waitUntil(predicate, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await sleep(100);
  }
  throw new Error("Timed out waiting for " + label);
}

function pass(name, condition, details = undefined) {
  const entry = {
    name,
    passed: Boolean(condition),
    ...(details === undefined ? {} : { details }),
  };
  evidence.checks.push(entry);
  if (!condition) {
    throw new Error("Acceptance check failed: " + name + " " + JSON.stringify(details ?? {}));
  }
}

function vector(position) {
  return {
    x: position.x,
    y: position.y,
    z: position.z,
  };
}

function distance(a, b) {
  const dx = Number(a.x) - b.x;
  const dy = Number(a.y) - b.y;
  const dz = Number(a.z) - b.z;
  return Math.sqrt(dx * dx + dy * dy + dz * dz);
}

async function writeEvidence() {
  await mkdir(OUT_DIR, { recursive: true });
  await writeFile(
    join(OUT_DIR, PLATFORM + ".json"),
    JSON.stringify(evidence, null, 2) + "\n",
    "utf8",
  );
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
