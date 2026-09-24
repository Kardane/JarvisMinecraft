# T08 Handoff — NeoForge Adapter

Date: 2026-09-24

## Baseline

- Base branch: `codex/t03-java-common-runtime`
- Protocol: 1.0
- Minecraft target: 1.21.8
- NeoForge target: 21.8.52
- Java: 21
- Owned path: `minecraft/neoforge/**`

## Delivered

### Dedicated-server mod entrypoint

- `JarvisNeoForgeMod`
- `@Mod(value = "jarvisminecraft", dist = Dist.DEDICATED_SERVER)`
- `META-INF/neoforge.mods.toml`
- exact Minecraft 1.21.8 runtime gate
- no client-only Minecraft/NeoForge API imports
- packaged JAR includes the T03 common runtime classes

### NeoForge events

Game-event bus listeners:

- `ServerStartedEvent`
- `ServerStoppingEvent`
- `ServerChatEvent`
- `PlayerEvent.PlayerLoggedOutEvent`
- `ServerTickEvent.Pre`
- `ServerTickEvent.Post`

`ServerChatEvent` is cancellable on the logical server. JARVIS direct/follow-up messages are cancelled before normal broadcast.

Server object access is still guarded with `MinecraftServer.isSameThread()`. If a chat event is unexpectedly observed off the server thread, JARVIS does not intercept or forward it to Brain and logs the condition once.

### OP authority

Authoritative access check:

`MinecraftServer.getPlayerList().isOp(ServerPlayer#getGameProfile())`

Current OP state is rechecked:

- before chat submission;
- before T03 CommonRuntime Tool execution;
- before private response delivery;
- immediately before self teleport.

Model claims and permission-node strings are not accepted as operator evidence.

### Chat session behavior

Aliases:

- 자비스
- jarvis, case-insensitive
- 재비스

Rules:

- non-OP chat never reaches Brain;
- partial tokens such as `자비스팅` are ordinary chat;
- direct invocation starts a 120 second session;
- accepted follow-up refreshes TTL;
- `대화 끝` ends locally and suppresses public broadcast;
- `!내용` bypasses Brain for one message and uses `ServerChatEvent#setMessage` to publish the text without the escape marker;
- logout, deop and TTL expiry invalidate local session/request state;
- Brain responses go only to the original current online OP;
- responses are plain `Component.literal` text and cannot create commands/click actions.

### Thread boundary

`MinecraftNeoForgePlatformAccess` owns all Minecraft server objects and rejects off-server-thread access.

`NeoForgeServerScheduler`:

- executes immediately when already on the server thread;
- otherwise queues through `MinecraftServer.execute`.

Entry into T03 `CommonRuntime.execute()` itself is scheduled onto the server thread because T03 validates `RequesterAuthority` before its nested Tool scheduler.

No Minecraft/NeoForge object crosses into common protocol DTOs or Brain state.

### Transport

- outbound loopback Brain WebSocket
- T03 `X-Jarvis-Secret` authentication
- Adapter hello -> Brain hello -> capabilities -> ACTIVE
- fresh T03 connection runtime on reconnect
- request binding requires:
  - serverId
  - requestId
  - requesterUuid
  - sessionId
- stale connection state is cleared and not replayed

Configuration is read from a system property first, then environment variable:

| Setting | System property | Environment | Default |
|---|---|---|---|
| server ID | `jarvis.serverId` | `JARVIS_SERVER_ID` | `main` |
| Brain URL | `jarvis.brainUrl` | `JARVIS_BRAIN_URL` | `ws://127.0.0.1:8181/ws` |
| secret | `jarvis.sharedSecret` | `JARVIS_SHARED_SECRET` | blank / rejected |
| reconnect delay | `jarvis.reconnectDelayTicks` | `JARVIS_RECONNECT_DELAY_TICKS` | 40 |

Only `ws` loopback URLs without credentials/query/fragment are accepted.
Blank/sample secrets are rejected.

### v0.1 Tool catalog

Exactly these seven Tools are advertised and registered:

- `get_server_status`
- `get_online_players`
- `get_player`
- `get_player_location`
- `get_nearby_players`
- `get_world_info`
- `teleport_staff`

No CoreProtect, WorldGuard or CMI capability is exposed by T08.

### Tool semantics

- server status:
  - tick-duration-derived TPS estimate
  - measured average MSPT
  - online player count
  - loaded chunk count
  - JVM used/max memory
- NeoForge MSPT/TPS is measured by `NeoForgeTickSampler` between `ServerTickEvent.Pre` and `Post` and reports source `NeoForgeTickSampler`.
- online-player list is bounded and paginated.
- player lookup is online UUID or exact name only.
- location/nearby/world reads use loaded server state only.
- `teleport_staff` always moves `requesterUuid`, never an arbitrary player.
- requester OP and target online state are sampled immediately before teleport.
- vanilla `ServerPlayer.teleportTo(...)` must report success.
- T03 actionId/deduplication/outcome-unknown policy remains authoritative.

## Verification

### Compile and contract verification

Commit `1b54b3e63023b3d4d3400888c75f80c5bd316ebe`, GitHub Actions run #44:

- Brain / Node: PASS
- Java / Gradle: PASS
- NeoForge compile against 21.8.52: PASS
- T08 fake-platform verification: PASS
- server-only import verification: PASS

Fake-platform coverage mirrors T06/T07:

- non-OP pass-through
- invocation token boundary
- direct/follow-up state
- public escape/local end/TTL
- loopback/secret config
- actor/request/session binding
- exactly seven v0.1 Tools
- server metrics + metric provenance
- bounded player pagination
- exact lookup/location/nearby/world reads
- requester-only teleport
- deopped requester action rejection

### Dedicated-server boot smoke

T08 includes an explicit ModDevGradle `t08BootSmoke` server run and `verifyT08BootSmoke` verification task.

The smoke:

1. builds the actual packaged JAR;
2. copies it into an isolated dedicated-server `mods/` directory;
3. accepts the EULA only in that isolated test directory;
4. starts NeoForge 21.8.52 with Minecraft 1.21.8;
5. requires the packaged JAR to reach JARVIS `ServerStartedEvent`;
6. writes a marker from the JARVIS entrypoint;
7. halts the test server;
8. fails verification if the marker is missing or invalid.

This exists because NeoForge dev-source runs do not model the packaged common-runtime layout accurately enough for JARVIS's multi-project JAR.

Verified packaged-JAR boot smoke:

- commit: `6bbeb3d5ba9455b30cddc796aad85e43099091e5`
- GitHub Actions run: #48
- `:minecraft:neoforge:prepareT08BootSmoke`: PASS
- `:minecraft:neoforge:runT08BootSmoke`: PASS
- loader reported `Minecraft JARVIS 0.1.0-SNAPSHOT (jarvisminecraft)`
- loader reported `NeoForge 21.8.52 (neoforge)`
- JARVIS reached `ServerStartedEvent` and logged `T08 dedicated server boot smoke OK`
- Minecraft server logged `Stopping server` and `Saving worlds`
- `:minecraft:neoforge:verifyT08BootSmoke`: PASS
- overall: `BUILD SUCCESSFUL`

The smoke task remains reproducible but opt-in:

```bash
./gradlew :minecraft:neoforge:verifyT08BootSmoke
```

It is intentionally not attached to the ordinary `check` lifecycle after evidence was captured, avoiding a full dedicated-server boot on every routine build.

## Remaining limits

T08 does not claim:

- a real player/client chat visibility test;
- a real player teleport test;
- Brain + Luna/Jev live E2E;
- modpack compatibility beyond NeoForge 21.8.52 / Minecraft 1.21.8.

Those remain T10 acceptance/live evidence.
