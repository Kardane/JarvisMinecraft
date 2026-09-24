# T03 Handoff — Java Common Runtime

Date: 2026-09-24

## Baseline

- Base branch: `main`
- Predecessor commit: `4e146771cf813f83cfdf5d0d842600be55afa329` (T02 merged)
- Protocol contract: `1.0`
- Owned path: `minecraft/common/**`

## Delivered

- Typed protocol 1.0 DTOs for envelope, payloads, Tool arguments/results and errors.
- Strict Gson-based codec with:
  - 64 KiB UTF-8 cap
  - exact-field validation
  - UUID/date-time validation
  - Tool-specific range validation
  - state-changing/read-only `actionId` rules
  - encode/decode round-trip support
- Common runtime:
  - Tool registry
  - scheduler SPI
  - current-OP authority SPI
  - deadline validation and timeout behavior
  - `toolCallId` deduplication per connection
  - `actionId` deduplication across reconnects
  - state-changing timeout -> `OUTCOME_UNKNOWN`
- Transport/authentication:
  - `X-Jarvis-Secret` constant
  - placeholder/blank secret rejection
  - constant-time comparison helper
  - JDK WebSocket client
  - inbound/outbound protocol size enforcement
- `AuditSink` SPI for T09 consumption.
- Common-only verification runner wired into Gradle `check`.

## Verification

Verified branch commit before this handoff document:

`c5ecb4f9613416748eacc903198a23377519e6d8`

GitHub Actions run #16:

- Brain / Node: PASS
- Java / Gradle: PASS
- `:minecraft:common:compileJava`: PASS
- `:minecraft:common:compileTestJava`: PASS
- `:minecraft:common:t03Verification`: PASS
- Output: `T03 verification OK`
- Overall: `BUILD SUCCESSFUL`

T03 verification covers:

- 31 valid T01 fixtures
- 9 invalid T01 fixtures
- encode/decode round-trip
- platform import isolation
- shared-secret validation
- expired deadline rejection before scheduler
- fake scheduler execution
- duplicate Tool call no re-execution
- reconnect action replay rejection

## Integration contract for T06/T07/T08

Platform adapters should provide:

- `RequesterAuthority`: authoritative online + OP check from the platform.
- `ServerScheduler`: bridge into the platform-safe server execution context.
- Tool handlers registered in `ToolRegistry`.
- A fresh `CommonRuntime.ConnectionRuntime` per Brain transport connection.
- The active Tool set derived from verified runtime capabilities, not model claims.

No adapter should pass Bukkit, Fabric, NeoForge or NMS objects into common DTOs.

## Remaining limits

- Actual Paper/Fabric/NeoForge server event/thread integration remains T06-T08.
- Actual Brain WebSocket handshake/E2E remains T04/T10.
- `AuditSink` has only an SPI here; durable JSONL/health behavior is T09.
- CoreProtect/WorldGuard Provider implementations remain T11/T12.
- CMI remains deferred to T14.
- No live Luna/Jev calls are part of T03.
