# ADR-0003: Platform Threading and OP Authority Boundary

- Status: Accepted
- Date: 2026-09-24
- Related work: T00

## Context

Minecraft events have different threading semantics across platforms, while AI/network/DB calls can be slow. In addition, actor information supplied by the Brain or LLM cannot be treated as trusted server authority.

## Decision

### OP Authority

In JARVIS v0.1, interactive actors are limited to players who are **currently online and recognized as OP by the server itself**.

Authority sources:

- Paper: server `isOp()`
- Fabric: `PlayerManager.isOperator(GameProfile)`
- NeoForge: the vanilla operator list / actual `PlayerList` operator check

Do not authorize a non-OP solely from a Brain payload such as `isOp=true`, LLM output, or a separate `jarvis.*` permission node.

Re-check authority at these three boundaries:

1. immediately before accepting JARVIS conversation input
2. immediately before Tool execution
3. immediately before result delivery

If de-op or logout is observed, discard the session and pending mutations.

### Threading

- Do not wait for AI HTTP, WebSocket, disk, or DB I/O on a Minecraft tick/server thread.
- Do not retain platform objects from event callbacks for long-lived use or pass them into the Brain.
- Convert server/world/player access to bounded DTO snapshots from the platform-guaranteed server execution context.
- Paper `AsyncChatEvent` may run asynchronously, so do not access Bukkit world APIs directly from the handler.
- Do not assume the detailed threading contract of Fabric chat callbacks before T07 runtime verification.
- Fix the exact NeoForge 1.21.8 event/mapping signatures from T02/T08 compilation results.

## Consequences

Benefits:

- Keeps the model/Brain outside the authority boundary.
- Reduces the risk of stale authority executing Tools during de-op/logout races.
- Prevents slow external I/O from directly blocking server ticks.

Costs:

- Every platform Adapter requires a scheduler/execution bridge.
- Read-only Tools must also respect snapshot boundaries.

## Verification Status

T00 reviewed API documentation and event semantics. De-op/logout races, private chat handling, and actual callback threads are verified with server/client tests in T06–T10.

## Official References

- Paper chat events: https://docs.papermc.io/paper/dev/chat-events/
- Paper ServerOperator: https://jd.papermc.io/paper/1.21.8/org/bukkit/permissions/ServerOperator.html
- Fabric ServerMessageEvents: https://maven.fabricmc.net/docs/fabric-api-0.133.4%2B1.21.8/net/fabricmc/fabric/api/message/v1/ServerMessageEvents.html
- Fabric PlayerManager: https://maven.fabricmc.net/docs/yarn-1.21.8%2Bbuild.1/net/minecraft/server/PlayerManager.html
- NeoForge events: https://docs.neoforged.net/docs/1.21.8/concepts/events/
