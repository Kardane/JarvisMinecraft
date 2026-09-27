# ADR-0007: Embedded Brain Migration and In-JVM Authority Boundary

- Status: Accepted
- Date: 2026-09-26
- Related work: Embedded Brain E0–E17
- Related ADRs: [ADR-0003](0003-platform-thread-and-op-boundary.md), [ADR-0005](0005-jev-luna-routing-authority-boundary.md), [ADR-0006](0006-provider-abstraction-threshold.md)

## Context

JarvisMinecraft historically separated Minecraft Adapters from a TypeScript Brain over a loopback WebSocket. After E12 parity verification, E13/E14 removed that process boundary and the Node production Brain. The production Brain is now embedded inside the Minecraft JVM.

The deployment goal is for a server administrator to run JARVIS with one platform-specific JAR/MOD plus model API keys. Session, budget, classification, and model orchestration therefore move into the JVM without recreating process-boundary state as equivalent Java objects.

## Decision

1. The production Brain uses the in-JVM Embedded Brain.
2. Retain the Jev + Luna dual-model architecture.
3. Use Jev only for request classification and deterministic Tool narrowing; Jev is not authority evidence.
4. Treat Luna Tool calls as untrusted proposals.
5. Keep final authority over Minecraft permissions and actual state in `CommonRuntime` and the platform Adapter.
6. Do not port the TypeScript Brain structure 1:1 to Java; migrate only required policy invariants.
7. Keep the Remote Brain only through the E12 parity baseline; remove it in E13/E14.
8. Perform AI HTTPS calls asynchronously and never wait for them on the Minecraft server/tick thread.
9. Reuse existing Java runtime components: `ToolName`, `ToolRegistry`, `AuditSink`, `CommonRuntime`, `RequesterAuthority`, `DeadlinePolicy`, `DeduplicationLedger`, and `ServerScheduler`.
10. Use one Java `ToolSpec` as the source of truth for Tool argument shape/range/schema, shared by the protocol parser and AI function schema.
11. Starting with E16, include the official OpenAI Java SDK runtime inside each platform's single deployment artifact.
12. Relocate SDK runtime dependency packages, including Jackson/OkHttp/Kotlin, under the JARVIS internal namespace to isolate them from Minecraft platform classpaths.
13. Do not bundle Gson provided by Minecraft; keep it compile-only.

## Policy Invariants to Preserve

- Accept requests only from currently online OPs.
- Re-check current online OP immediately before execution.
- Preserve request/session binding.
- Preserve the active Tool/capability allowlist.
- Preserve strict Tool argument validation.
- Preserve request deadline, Tool-call budget, and model-round budget.
- Serialize AI requests per session and keep the queue bounded.
- Execute state-changing Tools only after successful pre-execution audit.
- If a state-changing result cannot be determined before the deadline, return `OUTCOME_UNKNOWN`.
- Never automatically retry state-changing Tools.
- Block stale Tool execution and stale response delivery across de-op/logout/session-end races.
- Do not expose optional Provider Tools to Luna unless the Provider is actually active.

## Target Embedded Call Flow

```text
Minecraft Chat
  ↓
ChatSessionManager
  ↓
EmbeddedBrain
  ↓
RequestPlanner → Jev → DeterministicRoutePolicy
  ↓
ModelConversationLoop → Luna
  ↓
Tool proposal
  ↓
ToolArgumentCodec
  ↓
route / active Tool check
  ↓
ToolExecutionCoordinator / ScheduledToolCoordinator
  ↓
pre-execution AuditSink
  ↓
CommonRuntime
  ↓
Platform Adapter authority/state recheck
  ↓
Minecraft API
  ↓
Tool result
  ↓
Luna final response
```

## Single Ownership of State

### Session Lifecycle

`ChatSessionManager` owns session IDs, TTL, active state, termination, and invalidation.

The Embedded Brain does not maintain a separate session TTL store. It stores only model conversation history in `ConversationHistoryStore`.

### Tool Metadata

`Protocol.ToolName` is the source of truth for static Tool metadata: wire name, capability, state-changing flag, and risk.

`ToolRegistry` owns the set of Tools actually registered on the current server. Do not introduce a separate `BrainToolCatalog`.

### Tool Argument Validation

`ToolSpec` exclusively owns field shapes, UUID/range/selector constraints, and AI function-schema metadata. The preserved Remote protocol and Embedded Luna function calls both use the same `ToolArgumentCodec` facade over `ToolSpec` validation, so unknown/missing fields, UUIDs, ranges, and selector shapes are not implemented twice.

### Audit

The Embedded Brain uses the existing Java `AuditSink` contract. Do not introduce a separate `AuditPort`.

## Sidecar State Not Recreated in Embedded Mode

The following state existed only because of the WebSocket/process boundary and should not be moved into new Embedded abstractions:

```text
Brain-side connection registry
serverId → connection map
authenticated adapter connection state
hello/capabilities handshake state
ping/pong
shared-secret authentication
reconnect generation
Brain-side AdapterPort
remote Tool result connection binding
```

After E13/E14, this state and the related abstractions were removed from production source.

## Migration Sequence

```text
BrainGateway seam
  ↓
shared Java contract cleanup
  ↓
conversation history / budget / scheduler
  ↓
Jev / route / Luna
  ↓
EmbeddedBrain orchestration
  ↓
Remote/Embedded policy parity
  ↓
WebSocket removal
  ↓
Node Brain removal
```

After E12 shared fixtures verified deterministic policy parity and key safety invariants, E13 removed the WebSocket boundary and E14 removed the Node production Brain. Historical protocol/evaluation/evidence assets are retained.

## Consequences

### Benefits

- Operators no longer manage Node.js/npm or a Brain daemon.
- Process-boundary-only state and configuration can be removed.
- Existing Java authority, Tool registry, dedupe, and deadline policies are reused.
- Reduces duplicate ownership of session lifecycle, Tool metadata, and Tool argument validation.

### Costs

- TypeScript Brain policy required a semantics-preserving migration to Java.
- Remote and Embedded paths temporarily coexisted through E12.
- JVM Jev/Luna clients and an audit-sink implementation were required.

## E16 Packaging Decision

The OpenAI Java SDK packaging strategy was finalized in E16.

```text
platform artifact
  ├─ platform Adapter
  ├─ minecraft/common Embedded Brain
  └─ relocated OpenAI SDK runtime
       └─ io.github.kardane.jarvisminecraft.internal.shaded.*
```

Each platform produces one deployment JAR/MOD. Static artifact checks and clean-server boot smoke tests verify packaging/classloader boundaries. Jev continues to use JDK `HttpClient`, so no separate third-party HTTP runtime is added.
