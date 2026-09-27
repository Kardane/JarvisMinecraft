# ADR-0005: Jev + Luna Dual-Model Routing and Authority Boundary

- Status: Accepted
- Date: 2026-09-26
- Related work: Phase 6–7 refactoring
- Related ADRs: [ADR-0002](0002-ai-sdk-model-pins.md), [ADR-0003](0003-platform-thread-and-op-boundary.md)

## Context

The JARVIS Brain uses TypeSafe Jev and GPT-6 Luna together. Treating the two models as a simple fallback pair, or interpreting Jev confidence as execution authority, would blur the boundary between classification and authorization enforcement.

The Brain's historical `RemoteAdapter` also did not call Minecraft server APIs directly. Therefore, the Brain could verify only whether a requester/request/session binding was valid for the current WebSocket connection, not whether Minecraft currently recognized that player as OP.

## Decision

Retain the Jev + Luna dual-model architecture.

~~~text
OP input
  -> Jev classification
  -> deterministic route policy / Tool narrowing
  -> Luna response + Tool proposal
  -> Brain Tool/capability policy
  -> Adapter/CommonRuntime
  -> Minecraft server authority
~~~

Responsibilities are fixed as follows.

### Jev

- Classifies request intent and narrows the Tool candidates exposed to Luna.
- Confidence and category are not evidence of authority, approval, capability, or server state.
- On timeout, error, low confidence, or `UNCERTAIN`, do not broaden the mutation Tool set.

### Luna

- Proposes user responses and calls to allowed Tools.
- A Luna Tool call is an untrusted proposal, not an execution command.
- It cannot bypass Brain allowlist/capability/argument policy or Adapter checks.

### Brain

- Manages sessions, request budgets, route policy, Tool allowlists, and request bindings.
- The historical Brain AdapterPort method is named `isRequestBindingActive`.
- That method means only that requester/request/session are bound to the current connection; it does not establish Minecraft OP authority.

### Adapter / CommonRuntime

- The Minecraft Adapter is the final authority for current online + OP status.
- Immediately before Tool execution, re-check capability, current authority, actor/request binding, deadline, deduplication, and action state.
- If the result of a mutation is uncertain, finish with `OUTCOME_UNKNOWN` and do not retry automatically.

## Failure Policy

- Jev failure/uncertainty: stay read-only or ask a clarifying question. Do not newly authorize mutation Tools.
- Luna failure: use the fixed failure path and do not invent facts or Tool results.
- Brain request-binding failure: do not continue model, Tool, or response delivery.
- Adapter authority failure: reject execution and response delivery according to server authority.

## Consequences

Benefits:

- Clearly separates the classifier and generator roles while preserving the intended dual-model product architecture.
- Avoids overstating a Brain binding check as a real Minecraft authority check.
- Structurally prevents model confidence or output from expanding authority.

Costs:

- More Provider calls and observability points than a single-model design.
- Jev route quality and Luna Tool quality require separate evaluation.
- Model or SDK changes require regression verification at both stages.

## Change Conditions

Removing Jev or Luna, or merging their responsibilities, is not a routine refactor. A new ADR with evaluation evidence and failure policy must explicitly supersede this decision.
