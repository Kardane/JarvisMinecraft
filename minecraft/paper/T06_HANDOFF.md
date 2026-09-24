# T06 Handoff — Paper Adapter

Date: 2026-09-24

## Baseline

- Base branch: `codex/t03-java-common-runtime`
- Protocol: 1.0
- Minecraft/Paper target: 1.21.8
- Owned path: `minecraft/paper/**`
- T04/T05 are not required by this Java Adapter branch.

## Delivered

### Plugin lifecycle and transport

- JavaPlugin entrypoint: `JarvisPaperPlugin`
- Adapter connects outward to loopback Brain WebSocket.
- `X-Jarvis-Secret` is supplied by T03's JDK transport.
- Adapter hello -> Brain hello -> capabilities state transition.
- Every reconnect creates a fresh T03 `CommonRuntime.ConnectionRuntime`.
- Requests from an old connection are not rebound automatically.
- Only actual Paper-originated requestId/requesterUuid/sessionId tuples are accepted back from Brain.
- Invalid protocol or server binding disconnects the connection and schedules a reconnect.

### Configuration boundary

Defaults:
- server-id: main
- brain-url: ws://127.0.0.1:8181/ws
- shared-secret: CHANGE_ME
- reconnect-delay-ticks: 40

Runtime validation:
- serverId follows protocol syntax.
- only `ws` loopback hosts are accepted.
- credentials, query strings and fragments are rejected from Brain URI.
- blank/sample/short secrets are rejected by T03 shared-secret policy.
- the default CHANGE_ME intentionally disables startup until replaced.

### OP/private chat behavior

The Paper Adapter listens to Paper `ChatEvent` so the decision to suppress/publicly pass chat is made on the server thread.

Supported direct aliases:
- 자비스
- jarvis (case-insensitive)
- 재비스

Rules:
- non-OP chat is left on the normal Paper chat path and is never submitted to Brain.
- partial tokens such as `자비스팅` are not JARVIS calls.
- direct invocation starts a new 120 second session.
- accepted follow-up refreshes that session.
- `대화 끝` ends locally and sends cancel for outstanding session requests.
- `!내용` escapes one active-session message back to normal public chat.
- logout, OP revoke detection and TTL expiry invalidate local sessions and outstanding bindings.
- model responses are delivered only to the original online OP and only as `Component.text`.
- MiniMessage/click commands/console strings are never interpreted.

### Thread boundary

`BukkitPaperPlatformAccess` is the only class that owns Paper server objects.

It asserts primary-thread access for:
- OP checks
- player/world snapshots
- metrics
- private response delivery
- teleport initiation

`PaperServerScheduler` moves work onto the Paper primary thread.

Important T03 integration detail:
`PaperBrainConnection` schedules entry into `CommonRuntime.execute()` on the Paper thread because T03's `RequesterAuthority` check occurs before its nested Tool scheduler.

No Bukkit/Paper object is placed in protocol DTOs or kept by Brain/common.

### v0.1 Tool catalog

Advertised and registered:
- get_server_status
- get_online_players
- get_player
- get_player_location
- get_nearby_players
- get_world_info
- teleport_staff

Not advertised in T06:
- CoreProtect history Tools
- WorldGuard region Tools
- CMI Tools

Those remain T11/T12/T14.

### Tool behavior

- server status: Paper TPS, average MSPT, online count, loaded chunks, JVM memory with units/source/time.
- online players: UUID/name only, bounded pagination up to protocol limit.
- player lookup: online UUID or exact name only.
- player location: current loaded server state only.
- nearby players: same loaded world, bounded radius/limit, no new chunk loading.
- world info: loaded world only.
- teleport_staff:
  - mover is always requesterUuid
  - target must be online
  - requester must still be online + OP
  - target location is sampled immediately before Paper teleport
  - Paper `teleportAsync` completion must return true before success is reported
  - common actionId/deduplication/outcome-unknown policy remains in force

## Verification boundary

T06 verification is intentionally split:

1. CI compile against pinned Paper 1.21.8 API.
2. `T06VerificationMain` fake-platform contract tests.
3. Real Paper server boot/client behavior remains T10 acceptance evidence.

Fake verification covers:
- non-OP pass-through
- alias/token boundary
- direct/follow-up session behavior
- public escape/end/TTL
- loopback/secret config validation
- request actor/session binding
- exactly seven v0.1 Paper Tools
- server status units/source values
- player pagination
- exact player lookup
- location/nearby/world read paths
- requester-only teleport semantics
- deopped requester teleport rejection

## Remaining limits

- No live Paper server/client E2E is claimed in T06.
- No Luna/Jev live call is part of T06.
- T09 owns durable audit/config/operations implementation beyond this Adapter's minimal startup config.
- T11/T12/T13 own Paper plugin integrations and provider registry.
- Folia is not supported by this initial Paper Adapter.
