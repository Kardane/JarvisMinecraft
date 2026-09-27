# Minecraft JARVIS Communication Contract 1.0

> **E14 status (2026-09-27):** This document preserves the protocol 1.0 contract from the Remote Brain era as historical/compatibility material. The current production runtime has no Adapter ↔ Brain WebSocket boundary; authority, Tool, deadline, and deduplication policies are enforced directly inside the JVM by `EmbeddedBrain` and `CommonRuntime`. Do not interpret the wire details below as current operational procedures.

Date: 2026-09-24  
Related work: T01  
Prerequisites: docs/compatibility.md, docs/tools.md
Canonical schema: ../protocol/schema/protocol.schema.json

## 1. Purpose and Scope

This document fixes the **language-neutral communication contract** between Minecraft Adapters (Paper/Fabric/NeoForge) and the historical TypeScript Brain. T03–T10 implementations consume this contract and must not independently change envelope, authority, or error semantics for platform convenience.

The current protocol version is **1.0**.

T01 fixes the wire contract only. WebSocket library choice, Java/TypeScript serializer/validator implementation, and build/CI integration belong to T02/T03/T04.

## 2. Transport Layer

- The Brain opens a WebSocket server on a loopback address.
- The Adapter connects to the Brain.
- The shared secret is sent in the **X-Jarvis-Secret** header during the HTTP Upgrade request.
- Never place the secret in the URL, query string, protocol JSON, or audit log.
- The Brain must compare the shared secret in constant time.
- Reject empty secrets, sample secrets, and failed authentication.
- Even after authentication, process no business message except `hello` until hello validation succeeds.
- Each WebSocket message contains exactly one UTF-8 JSON object.
- Maximum message size is **65,536 bytes**. Apply the byte limit before JSON parsing.
- Compression may be chosen by the implementation, but the decompressed payload must not bypass the size limit.

## 3. Connection State Transitions

~~~text
DISCONNECTED
    |
    | WebSocket upgrade + secret validation
    v
TRANSPORT_AUTHENTICATED
    |
    | Adapter -> hello
    | Brain -> hello(accepted=true)
    v
HELLO_VERIFIED
    |
    | Adapter -> capabilities
    v
ACTIVE
    |
    | disconnect / protocol error / shutdown
    v
DISCONNECTED
~~~

Rules:

1. The Adapter hello `serverId` is bound to the authenticated connection.
2. Every later message on the same connection must use the same `serverId`.
3. Capabilities are exchanged again on every reconnect.
4. A reconnect is a new connection. Do not automatically revive sessions/actions from the previous connection.
5. If the protocol major version is incompatible, terminate with `UNSUPPORTED`.
6. The current 1.0 implementation accepts exactly `protocolVersion="1.0"`.

## 4. Common Envelope

Every protocol message includes **all** fields below. Identifiers that are not used are sent as `null` rather than omitted.

| Field | Format | Meaning |
|---|---|---|
| protocolVersion | "1.0" | wire-contract version |
| type | string | message type discriminator |
| messageId | UUID | identifier for an individual wire message |
| requestId | UUID or null | identifier spanning one user request and its entire Tool loop |
| serverId | 1–64 chars | authenticated Minecraft server identifier |
| sessionId | UUID or null | OP conversation session identifier |
| requesterUuid | UUID or null | UUID of the actual Minecraft player making the request |
| sentAt | RFC3339 date-time | send time |
| deadlineAt | RFC3339 date-time or null | absolute time after which the request is no longer valid |
| payload | object | payload for the message type |

Tool messages additionally carry `toolCallId`. State-changing Tools also carry `actionId`.

### Identifier Semantics

- `messageId`: new value for each send; used for transport-duplicate detection.
- `requestId`: remains constant from one OP message through the final `chat.response`.
- `sessionId`: remains constant within the 120-second conversation started by a direct invocation.
- `toolCallId`: identifies one Tool attempt; each Tool call gets a different value even within the same `requestId`.
- `actionId`: identifies one state-changing operation. In v0.1, it is used only by `teleport_staff`.

`requesterUuid` is **not authority evidence**. It is only an identifier used to validate binding against the request actually admitted by the Adapter.

## 5. Message Types and Direction

| type | Direction | request/session/requester | Description |
|---|---|---|---|
| hello | bidirectional | null | verify protocol/platform instance |
| capabilities | Adapter -> Brain | null | list of capabilities/Tools actually available now |
| chat.message | Adapter -> Brain | required | JARVIS input sent by an OP; input remains visible in server chat |
| chat.response | Brain -> Adapter | required | plain-text response bound to the request session; Adapter re-checks requester OP authority and session, then broadcasts to all connected players |
| tool.request | Brain -> Adapter | required | allowlisted Tool execution request |
| tool.result | Adapter -> Brain | required | structured Tool result |
| cancel | bidirectional | requestId required | best-effort cancellation of an in-progress request |
| error | bidirectional | nullable by context | connection/request-level error |
| ping | bidirectional | null | keepalive |
| pong | bidirectional | null | ping response |

Tool-execution failure is represented as **`ERROR`/`UNSUPPORTED` in `tool.result`**, not as an `error` message. The `error` message is reserved for protocol/connection/request-orchestration failures.

## 6. Hello and Capability

### Adapter hello

Included information:

- adapterInstanceId
- platform: paper / fabric / neoforge
- minecraftVersion: 1.21.8
- adapterVersion
- platformVersion

The Brain does not infer authority from these values. An incompatible platform/version is rejected explicitly.

### Brain hello

- brainInstanceId
- brainVersion
- accepted=true

There is no `accepted=false` form in the protocol. Rejection is represented by `error` followed by connection close.

### Capability

A capability exists only after **real runtime verification of the Provider/platform feature**. Mere plugin-file presence is not a capability.

Initial capability names:

- server.status
- player.list
- player.lookup
- player.location
- player.nearby
- world.info
- staff.self_teleport
- history.lookup
- region.lookup
- region.protection

The Brain does not expose a Tool to the model if it is absent from the `tools` array in the capabilities message. The Adapter still re-checks allowlist/capability at execution time.

## 7. OP and Actor Contract

The only v0.1 interaction actor is a player who is **currently online and actually recognized as OP by the server**.

Authoritative checks:

- Paper: server API `isOp()`
- Fabric: PlayerManager.isOperator(GameProfile)
- NeoForge: actual vanilla operator registry / `PlayerList` check

The following are not authority evidence:

- Brain-internal flags
- LLM output
- arbitrary booleans in request JSON
- satisfying only a `jarvis.*` permission node

The protocol schema intentionally has no `isOp` field.

The Adapter re-checks authority at least three times:

1. immediately before accepting `chat.message`
2. immediately before executing `tool.request`
3. immediately before delivering `chat.response`

If de-op or logout is observed, discard the session and do not begin new in-progress mutation execution.

## 8. Chat Session Contract

~~~text
NONE
  |
  | OP starts with an independent wake word
  v
ACTIVE (TTL 120 seconds)
  |  accepted follow-up
  |--------------------+
  |                    |
  +---- TTL refresh <--+
  |
  +--> "대화 끝" (end conversation) -> ENDED
  |
  +--> 120 seconds idle -> EXPIRED
  |
  +--> deop/logout/disconnect -> INVALIDATED
~~~

Adapter rules:

- Direct-invocation aliases: `자비스` / `jarvis` / `재비스`.
- Accept a wake word only when it is an independent token at the start of the message.
- English matching is case-insensitive.
- Do not treat a substring such as `자비스팅` as an invocation.
- In an active session, `"대화 끝"` terminates locally and is not sent to the Brain.
- In an active session, `"!내용"` sends only that message as ordinary chat and does not send it to the Brain.
- Do not send ordinary non-OP chat to the Brain or model.
- Keep OP JARVIS input visible in server chat and broadcast JARVIS responses to all connected players. Send only that OP's JARVIS input to the Brain.

The session key is `(serverId, requesterUuid, sessionId)`. Do not use player name as a key.

## 9. Request Serialization and Budgets

Serialize user requests within a session.

Initial limits:

- 1 in-flight request per session
- 2 queued requests per session
- 4 in-flight conversations per server
- 16 queued requests globally
- at most 8 Tool calls per request
- at most 4 model round trips per request
- 30-second overall request deadline
- 3-second Jev deadline
- 5-second ordinary read-only Tool deadline

If a limit is exceeded, terminate with `BUSY` or `TIMEOUT`; never create an unbounded queue.

`deadlineAt` is an absolute timestamp. The receiver uses the earlier of its own limit and the incoming deadline. Reject a business message where `deadlineAt <= sentAt` with `INVALID_ARGUMENT`.

## 10. Tool Execution and Deduplication

### Read-only Tool

`actionId` must be `null`.

If the same `toolCallId` is seen again on the same connection:

- return `BUSY` if it is still executing;
- reuse the same terminal result if one is retained;
- do not treat it as a new Tool execution and amplify calls.

### State-changing Tool

In v0.1 this applies only to `teleport_staff`.

- `actionId` is a required UUID.
- The Adapter stores execution state by `actionId`.
- Never execute the same `actionId` twice.
- If ACK/result loss makes the outcome impossible to confirm, return `OUTCOME_UNKNOWN`.
- Never automatically re-execute `OUTCOME_UNKNOWN`.
- After reconnect, reject state-changing requests from a previous connection/session.
- A network timeout is not evidence that the Minecraft mutation was cancelled.

Initial action states:

~~~text
UNSEEN
  |
  v
EXECUTING
  |------> SUCCEEDED
  |------> FAILED
  +------> OUTCOME_UNKNOWN
~~~

## 11. Cancellation

`cancel` is a best-effort orchestration signal.

reason:

- CLIENT_DISCONNECTED
- SESSION_ENDED
- DEADLINE_EXCEEDED
- OP_REVOKED
- SHUTDOWN

It does not mean undoing an already completed Tool. In particular, do not infer failure or rollback merely because cancel/timeout arrives after a mutation has started.

## 12. Common Tool Result Contract

Every Tool result contains these fields:

| Field | Meaning |
|---|---|
| status | OK / EMPTY / ERROR / UNSUPPORTED |
| data | Tool-specific structured data; null for ERROR/UNSUPPORTED |
| error | structured error for ERROR/UNSUPPORTED; null for OK/EMPTY |
| observedAt | time the server/Provider observed the fact |
| source | actual source such as Paper, Fabric, NeoForge, CoreProtect, or WorldGuard |
| truncated | whether only part of the result was returned because of limits |

`EMPTY` means the query completed normally but its record/list is empty. `NOT_FOUND` means the requested target itself does not exist. Do not conflate them.

## 13. Error Codes

Fixed error codes:

| Code | Meaning |
|---|---|
| UNAUTHORIZED | authentication/current OP/actor-binding failure |
| INVALID_ARGUMENT | includes semantic validation failure after schema validation |
| UNSUPPORTED | unsupported protocol/capability/feature |
| NOT_FOUND | exact target does not exist |
| AMBIGUOUS_TARGET | cannot resolve to one UUID |
| BUSY | queue/same-Tool execution/concurrency limit |
| TIMEOUT | cannot complete before deadline |
| PROVIDER_UNAVAILABLE | registered Provider is currently unavailable |
| CANCELLED | explicit cancellation |
| OUTCOME_UNKNOWN | cannot safely determine mutation outcome |
| INTERNAL | internal failure whose detailed stack must not be exposed |

`error.message` must be a safe sentence suitable for player display. Never include API keys, stack traces, SQL, or filesystem secrets.

## 14. Retry Rules

- Never automatically retry state-changing Tools.
- Read-only Tools may be retried only within the deadline and under bounded policy.
- Do not stack SDK retries and JARVIS retries for the same failure and amplify call volume.
- Retry does not create a new `toolCallId`. A transport retry of the same logical Tool attempt retains the original `toolCallId`.
- If it is unclear whether a Provider returned a result for a state-changing operation, terminate with `OUTCOME_UNKNOWN`.

## 15. Plain-Text Output

`chat.response.payload.text` is **always plain text**.

The Adapter does not interpret model strings as:

- MiniMessage markup
- clickable commands
- console command
- JSON chat component command
- URL-based automatic execution

If platform-specific color/branding is needed, the Adapter may add only a trusted fixed prefix.

## 16. Semantic Conditions That Must Be Validated Outside Schema

Passing JSON Schema is not execution authorization. The following are runtime validations:

- raw UTF-8 message <= 65,536 bytes
- deadlineAt > sentAt
- matches the `serverId` bound to the connection
- `requestId/sessionId/requesterUuid` match a request actually registered by the Adapter
- requester is currently online + OP
- currently present in capability/Tool allowlist
- request budget/queue limits
- Tool-specific server-state conditions
- explicit movement intent for `teleport_staff`
- duplicate `actionId/toolCallId` handling
- re-check requester online + OP before result delivery

## 17. Platform Contract Review

When T01 was written, a static contract review was performed against the three platform boundaries established in T00.

| Item | Paper | Fabric | NeoForge |
|---|---|---|---|
| no platform objects in protocol | PASS | PASS | PASS |
| OP decision remains Adapter authority | PASS | PASS | PASS |
| scheduler/execution bridge implementable | PASS | PASS | PASS |
| broadcast response after OP/session re-check | PASS | PASS | PASS |
| Tool DTOs are platform-neutral | PASS | PASS | PASS |
| exact runtime event signature live verification | T06/T10 | T07/T10 | T08/T10 |

This table is not real-server E2E evidence. Adapter implementers must re-review this contract when starting T06–T08 and submit a T01 contract-change request if implementation requires a compatibility-breaking change.

## 18. Fixture Rules

Java and TypeScript both read `protocol/fixtures/manifest.json` and validate the same fixtures.

- `valid/*`: all must pass schema validation
- `invalid/*`: all must fail schema validation
- Draft 2020-12
- UUID/date-time format validation enabled
- no type coercion
- unknown fields prohibited

T01 performed schema/fixture structural verification; build/CI automation is connected in T02.

### Contract Asset Status After E14

E13 removed Java `ProtocolCodec`, `ProtocolMessage`, WebSocket transport, and connection binding; E14 removed the TypeScript Brain runtime and AJV validator.

`protocol/schema/protocol.schema.json` and fixtures are retained for:

- historical compatibility reference for the Remote/Embedded migration
- interpretation of historical T10 evidence
- Tool/envelope shape regression analysis
- historical source of `GeneratedContractConstants.java`

In the current production Java path, `ToolArgumentCodec` owns Tool argument validation, `Protocol.ToolName` owns Tool metadata, `ToolRegistry` owns actual active Tools, and `CommonRuntime` owns authority/deadline/deduplication.


## 19. T01 Handoff

T03 Java common:

- Generate envelope/DTO types from schema or keep them 1:1 with schema.
- Do not add platform objects to DTOs.
- Implement authentication, binding, deadline, and `toolCallId/actionId` deduplication.

T04 Brain:

- Implement session/request binding, queues/budgets, and active Tool allowlists according to this contract.
- Do not use `requesterUuid` or model output as authority evidence.

T05 AI:

- Expose only Tool schemas present in capabilities to the model.
- Model-generated arguments must pass both schema and policy checks.

T06~T08 Adapter:

- Convert platform events to these protocol DTOs.
- Re-check current OP and actor binding both at Tool execution and result delivery.


## Phase 7 scheduling control Tools

For analysis/regression, the Embedded runtime retains the following control Tools in the protocol schema:

- `schedule_action`
- `cancel_scheduled_action`

Both are state-changing control Tools, so wire fixtures require a non-null
`actionId`. The nested Tool for `schedule_action` is restricted to
`teleport_staff / weather_set / time_set`.

`schedule_action` does not keep the original Tool request open for 60 seconds.
On successful registration it immediately returns a schedule ID; actual delayed/repeated
execution runs inside the Embedded runtime with a new `toolCallId/actionId/deadline`.
