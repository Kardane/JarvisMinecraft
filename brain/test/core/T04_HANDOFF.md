# T04 Handoff — Brain Core

Date: 2026-09-24

## Baseline

- Base: main at T02
- T03 is intentionally not required by T04 and remains a parallel PR.
- Owned implementation: brain/src/core/**
- Owned tests: brain/test/core/**

## Public ports for following tasks

### T05
Implement ModelPort from brain/src/core/ports.ts.

The model receives only:
- bound server/requester/session/request identifiers
- session-scoped conversation history
- verified capability descriptors
- verified active Tool descriptors
- remaining Tool/model budgets
- absolute request deadline

The model returns either a final plain-text response or a bounded Tool-call batch.

### T06/T07/T08
Implement AdapterPort.

The Adapter remains authoritative for current online OP state and must also enforce its own allowlist/binding checks. Brain re-checks isCurrentOperator at intake, immediately before Tool execution, and before response delivery.

### T09
Implement AuditPort.

State-changing Tools are refused unless the pre-execution audit record returns true. A failed post-execution audit never causes automatic Tool replay.

## Core invariants

- Session key: serverId + requesterUuid + sessionId.
- TTL: 120 seconds.
- 1 active request/session.
- 2 queued requests/session.
- 4 active conversations/server.
- 16 queued requests globally.
- 8 Tool calls/request.
- 4 model rounds/request.
- 30 second request deadline.
- Tool execution deadline: at most 5 seconds and never beyond request deadline.
- Reconnect invalidates prior sessions and queued work.
- Only catalogued + advertised + capability-backed Tools reach AdapterPort.
- teleport_staff receives an actionId; read-only Tools receive actionId=null.
- No generic command, SQL, code, file or console execution Tool exists.

## Verification

Run after Brain build:

node --test ./test/core/*.test.mjs

The tests cover OP denial, actor/session isolation, TTL expiry, deop during a turn, Tool allowlist rejection, pre-execution audit gating, actionId generation, Tool budget no-partial-execution, result binding mismatch, reconnect invalidation, and queue/concurrency limits.
