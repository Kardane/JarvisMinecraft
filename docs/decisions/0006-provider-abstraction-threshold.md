# ADR-0006: Optional Provider Abstraction Threshold

- Status: Accepted
- Date: 2026-09-26
- Related work: Phase 8 refactoring
- Related ADR: [ADR-0004](0004-paper-provider-boundary.md)

## Context

Paper optional Providers integrate CoreProtect, WorldGuard, and CMI through public API boundaries. The refactoring review considered extracting shared infrastructure for:

- bounded worker pool + queue
- timeout/cancellation wrapper
- cursor/snapshot store
- Provider lifecycle/registration framework
- ServiceLoader or separate Provider artifacts instead of reflection

Although all three are Providers, their execution models are not the same.

### CoreProtect

- Does not wait for history DB/API queries on the Minecraft primary thread.
- Owns a dedicated bounded worker pool and queue.
- Requires query timeout and cancellation.
- Uses a bounded snapshot cache whose paging cursor is bound to requester/session/query.
- Owns worker/timer resources and therefore requires lifecycle close.

### WorldGuard

- Performs synchronous read-only queries in the current server execution context.
- Converts region/member/flag results into bounded DTOs.
- Does not own a worker pool, timeout scheduler, or cursor snapshot.

### CMI

- Performs synchronous read-only API queries for currently online player profiles.
- Returns only bounded nickname/AFK values.
- Does not own a worker pool, timeout scheduler, or cursor snapshot.

## Decision

Do not extract shared Provider infrastructure such as `BoundedAsyncExecutor` or `BoundedSnapshotStore` at this stage.

Keep CoreProtect executor/timeout/cursor implementation inside the CoreProtect domain. Do not add unnecessary queues, timers, or snapshot lifecycles merely to force WorldGuard and CMI behind the same asynchronous abstraction.

Extract shared infrastructure **only when a second real consumer requires the same failure semantics and lifecycle**.

### Conditions for Extracting Bounded Async Infrastructure

Revisit extraction when another Provider requires all of the following:

1. a blocking Provider API that must execute off the server thread
2. bounded concurrency and a bounded queue
3. the shorter of the request deadline and Provider-specific timeout
4. terminalizing the future on timeout before best-effort worker cancellation
5. exposing queue saturation as an explicit `BUSY` result
6. closing owned executors during Provider shutdown

Do not extract merely because an API returns `CompletableFuture`.

### Conditions for Extracting Snapshot/Cursor Infrastructure

Revisit extraction when another Provider requires all of the following:

1. retaining the initial query result as a bounded snapshot
2. a cursor that represents snapshot ID and offset
3. binding the cursor to requester/session/query identity
4. bounding both TTL and maximum snapshot count
5. failing stale or mismatched cursors closed with `INVALID_ARGUMENT`

Do not apply this abstraction to Providers that only need page numbers.

### IntegrationRegistry

Keep the current reflection-based module loading.

Reflection is not used for arbitrary internal access; it isolates optional plugin API linkage from the core Paper class-loading boundary. `IntegrationRegistry` first registers Provider Tools into a staged `ToolRegistry`, then atomically applies them to the main registry only if module initialization and all Tool registrations succeed.

Revisit ServiceLoader or separate Provider artifacts if any of these conditions occur:

- the Provider count grows enough that maintaining a static module catalog becomes difficult
- optional API dependencies repeatedly destabilize Paper artifact build/linkage
- Providers need independent distribution/versioning
- reflection entrypoint signatures change repeatedly

The current three Providers do not justify that cost.

## Timeout Ordering

A Provider timeout must terminalize the result before attempting worker cancellation. Interrupting the worker first can race with the worker completing a normal/error result before the timeout result.

CoreProtect follows this order:

1. atomically attempt to complete with a `TIMEOUT` result
2. cancel the worker future only if timeout completion wins
3. if the worker result already completed, the timeout does not overwrite it

## Consequences

Benefits:

- Avoids forcing Providers with genuinely different execution semantics into one framework.
- Keeps CoreProtect complexity only where it is required.
- Keeps WorldGuard/CMI synchronous paths from becoming unnecessarily complex.
- Allows a future shared API to be designed from real requirements when a second consumer appears.

Costs:

- Executor/snapshot implementation code remains inside CoreProtect for now.
- If a future Provider needs the same patterns, a safe extraction refactor will be required then.

## Verification Boundary

- T11: CoreProtect bounds, paging/cursor binding, timeout, partial result, Tool registration
- T12: WorldGuard bounds/protection-query semantics
- T13: optional dependency matrix, fail-closed loading, atomic Tool registration, Provider shutdown
- T14: CMI online-profile contract

These verifications intentionally preserve the different Provider semantics.
