# T07 Handoff — Fabric Adapter

Date: 2026-09-24

## Baseline

- Base branch: `codex/t03-java-common-runtime`
- Protocol: 1.0
- Minecraft target: 1.21.8
- Fabric Loader: 0.17.2
- Fabric API: 0.133.4+1.21.8
- Yarn: 1.21.8+build.1
- Owned path: `minecraft/fabric/**`

## Delivered

### Dedicated-server entrypoint

- `JarvisFabricMod` is registered as the Fabric `main` entrypoint.
- `fabric.mod.json` remains `environment: server`.
- No client-only Minecraft or Fabric API import is permitted by the module verification task.
- Runtime is enabled only for exact Minecraft 1.21.8.

### Fabric events

T07 uses:

- `ServerLifecycleEvents.SERVER_STARTED`
- `ServerLifecycleEvents.SERVER_STOPPING`
- `ServerMessageEvents.ALLOW_CHAT_MESSAGE`
- `ServerPlayConnectionEvents.DISCONNECT`
- `ServerTickEvents.END_SERVER_TICK`

The chat callback's public API does not document a server-thread guarantee.

Therefore the Adapter follows a fail-open/public, fail-closed-to-Brain policy:
- when the callback is on the server thread, normal JARVIS routing is evaluated;
- when unexpectedly off-thread, the chat message is left on the normal server chat path;
- an off-thread message is never submitted to Brain;
- the unexpected condition is logged once.

Actual callback-thread behavior on a dedicated 1.21.8 server remains T10 evidence.

### OP / actor authority

Authoritative Fabric OP status is:

`PlayerManager.isOperator(ServerPlayerEntity#getGameProfile())`

The Adapter rechecks current operator state:
- before a JARVIS chat message is submitted;
- before T03 CommonRuntime executes a Tool;
- before a private Brain response is delivered;
- immediately before `teleport_staff`.

No permission-node/model field is accepted as OP evidence.

### Private chat sessions

Direct aliases:
- 자비스
- jarvis, case-insensitive
- 재비스

Rules:
- non-OP chat is never sent to Brain;
- partial tokens such as `자비스팅` are normal chat;
- direct invocation starts a 120 second session;
- follow-up messages refresh TTL;
- `대화 끝` terminates the session locally and suppresses broadcast;
- `!내용` bypasses Brain for one message and is publicly broadcast.

Fabric signed-message handling does not rewrite `!내용` to strip the leading exclamation mark. The marker may therefore remain visible in public chat. This preserves the signed chat message instead of mutating it.

Disconnect, OP revocation and TTL expiry invalidate local session/request state and send best-effort cancellation for outstanding Brain requests.

### Thread boundary

`MinecraftFabricPlatformAccess` owns all Minecraft server objects and rejects off-server-thread access.

`FabricServerScheduler`:
- executes immediately if already on the server thread;
- otherwise enqueues through `MinecraftServer.execute`.

As with T06, entry into T03 `CommonRuntime.execute()` itself is scheduled onto the server thread because T03 performs `RequesterAuthority` before its nested Tool scheduler.

No Minecraft/Fabric object crosses into common protocol DTOs or Brain state.

### Transport

- Adapter connects outward to the configured loopback Brain WebSocket.
- T03 `X-Jarvis-Secret` transport authentication is used.
- Adapter hello -> Brain hello -> capabilities -> ACTIVE.
- Reconnect creates a fresh T03 connection runtime and clears request bindings.
- Brain messages must match the current Fabric-originated request binding:
  - requestId
  - requesterUuid
  - sessionId
  - serverId
- stale/reconnected state is not automatically revived.

Configuration is read from system properties first, then environment variables:

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

No CoreProtect/WorldGuard/CMI capability is exposed by T07.

### Tool behavior

- Server status:
  - tick-time-derived TPS estimate
  - average MSPT
  - online player count
  - loaded chunk count
  - JVM used/max memory
- The derived TPS metric source is explicitly `FabricTickTimes`; it is not represented as a native 1-minute Paper TPS sample.
- Online players use bounded pagination.
- Player lookup is online UUID or exact name only.
- Location and nearby-player reads use loaded server state only.
- World lookup accepts canonical registry ID and loaded-world path alias.
- `teleport_staff`:
  - mover is always requesterUuid;
  - target must currently be online;
  - requester must currently be operator;
  - target position/world is sampled at execution time;
  - vanilla `ServerPlayerEntity.teleport(...)` must return success;
  - T03 actionId/deduplication/outcome-unknown policy remains authoritative.

### Artifact boundary

The Fabric JAR packages the T03 common runtime classes.

Fabric Loader, Fabric API and Minecraft are not shaded into the release artifact.

## Verification

Verified head before this handoff document:

`1f0b9e43ea911aefc67f8f8669cc72d41ee64958`

GitHub Actions run #38:

- Brain / Node: PASS
- Java / Gradle: PASS
- `:minecraft:fabric:verifyNoClientImports`: PASS
- `:minecraft:fabric:compileJava`: PASS
- `:minecraft:fabric:compileTestJava`: PASS
- `:minecraft:fabric:t07Verification`: PASS
- output: `T07 verification OK`
- `:minecraft:fabric:remapJar`: PASS
- overall: `BUILD SUCCESSFUL`

T07 fake-platform verification covers:

- non-OP pass-through
- invocation alias/token boundary
- direct/follow-up state
- public escape/local end/TTL
- loopback and secret configuration
- request actor/session binding
- exactly seven v0.1 Tools
- server status values and derived TPS provenance
- bounded online-player pagination
- exact player lookup
- player location
- nearby-player lookup
- loaded-world lookup
- requester-only teleport
- deopped requester action rejection

## Remaining limits

T07 does not claim:

- actual Fabric dedicated-server boot evidence;
- actual 1.21.8 chat callback thread observation;
- signed-chat client UI behavior;
- live private-chat visibility verification;
- live teleport verification;
- Luna/Jev live model calls.

Those are T10 acceptance/live evidence.

Fabric-specific integrations analogous to Paper CoreProtect/WorldGuard are not part of the current roadmap.
