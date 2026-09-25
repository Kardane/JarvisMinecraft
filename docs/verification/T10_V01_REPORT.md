# T10 v0.1 acceptance report

Date: 2026-09-25  
Branch: `codex/t10-acceptance`  
Scope: v0.1 acceptance evidence for T03-T09

## Overall status

**PARTIAL — release gate not complete.**

The deterministic policy suite and the real Minecraft platform/client scenarios have strong passing evidence. The v0.1 release gate remains incomplete because:

1. A09 has not been run against the real TypeSafe Jev provider; `TYPESAFE_API_KEY` is not configured in the repository Actions environment.
2. A10 has not been run against the real OpenAI + TypeSafe providers; `OPENAI_API_KEY` and `TYPESAFE_API_KEY` are not configured.
3. A11 live MSPT testing passed on Paper and NeoForge but the first Fabric measurement exceeded the <=5ms target on a GitHub-hosted runner. A same-scenario retry was requested without changing the threshold or formula.
4. The repository still has no production Brain WebSocket server entrypoint. T10 uses a test-only authenticated WebSocket gateway under `tests/acceptance/**` to connect the real Adapters to `BrainCore`. This proves the Adapter/protocol/Core path but is not a deployable production Brain daemon.

No missing gate is converted into a PASS.

## Evidence layers

### 1. Deterministic policy / state-machine acceptance

Stable successful evidence:

- workflow: `T10 Acceptance TEMP`
- run: #3
- run id: `35948489552`
- commit: `cae359ed5181636723ad40064188a6b0c66d65bb`
- result: PASS

The deterministic suite passed 10/10 tests:

| Acceptance | Result | Evidence |
|---|---|---|
| A01 | PASS | non-OP reaches model/Tool/response 0 times; OP response stays requester-bound |
| A02 | PASS | deop after model decision prevents Tool execution and response |
| A03 | PASS | follow-up rejected after 120-second session expiry |
| A04 | PASS | same display name across two servers remains server/UUID/session isolated |
| A05 | PASS | Tool evidence retains metric unit/time; state change receives actionId |
| A06 | PASS | unregistered Tool is never executed |
| A07 | PASS | uncertain state-changing outcome is not automatically retried |
| A08 | PASS | Jev failure exposes read-only Tools only; Luna model is not substituted |
| A11 queue bounds | PASS | 4 active/server, 16 global queued, 2 queued/session |
| A12 | PASS | unavailable provider Tool is not exposed/executed |
| A13 | PASS | audit I/O failure makes state change fail closed |

A14 is v0.2-only and is not a v0.1 gate.

### 2. Real Minecraft server + protocol-client acceptance

Stable successful evidence:

- workflow: `T10 Acceptance TEMP`
- run: #3
- run id: `35948489552`
- commit: `cae359ed5181636723ad40064188a6b0c66d65bb`

Test shape:

- actual Minecraft 1.21.8 dedicated server
- actual packaged platform Adapter JAR
- authenticated loopback test Brain gateway
- three real Minecraft protocol sessions via Mineflayer 4.39.0:
  - `AdminA`: OP
  - `NonOp`: non-OP
  - `OtherOp`: second OP
- offline-mode server only for isolated CI test identities

These are real protocol clients, not Mojang GUI clients. No GUI-client visual evidence is claimed.

| Platform | Server boot | JARVIS loaded | Live checks | Result |
|---|---:|---:|---:|---|
| Paper 1.21.8 | PASS | PASS | 22/22 | PASS |
| Fabric 1.21.8 | PASS | PASS | 22/22 | PASS |
| NeoForge 21.8.52 / MC 1.21.8 | PASS | PASS | 22/22 | PASS |

The live scenarios verify:

- non-OP JARVIS-looking chat causes zero Brain chat submissions;
- requester OP receives private JARVIS response;
- non-OP and other OP do not receive that response;
- direct OP chat reaches Brain exactly once;
- follow-up is forwarded as `FOLLOW_UP`;
- `!내용` does not reach Brain and is public;
- `대화 끝` is private/local and stops further session forwarding;
- player location returned by the Adapter matches the target protocol client's observed position;
- `teleport_staff` actually moves the requesting OP to the target;
- server metrics contain unit + observation timestamp;
- state-changing Tool request has an actionId;
- after deop, JARVIS-looking chat does not reach Brain;
- after re-op, a new session can start;
- unregistered command execution remains zero;
- missing CoreProtect/WorldGuard provider Tools are not advertised;
- Brain gateway restart causes Adapter reconnect;
- server remains responsive after Brain reconnect.

Evidence artifacts from run #3:

- Paper: artifact `10786844612`
- Fabric: artifact `10787672426`
- NeoForge: artifact `10787279999`

### 3. A11 live MSPT load measurement

A later live run added a fixed dual-OP status-query load without changing the product limits.

Workflow run id: `36104584773`

Method:

1. collect eight sequential baseline `get_server_status` MSPT observations;
2. run twelve rounds of two simultaneous OP status requests;
3. collect 24 load MSPT observations;
4. calculate p95 for baseline and load;
5. require `loadP95 - baselineP95 <= 5ms`.

Observed results so far:

| Platform | Baseline p95 | Load p95 | Delta | Result |
|---|---:|---:|---:|---|
| Paper | 6.419 ms | 6.427 ms | +0.008 ms | PASS |
| Fabric first attempt | 50.375 ms | 92.359 ms | +41.984 ms | FAIL |
| Fabric same-scenario retry | 13.903 ms | 8.052 ms | -5.851 ms | PASS |
| NeoForge | 23.951 ms | 20.850 ms | -3.101 ms | PASS |

The Fabric failure is recorded as observed. The test threshold and calculation were not relaxed. A same-scenario retry, using the same workflow run and unchanged test, passed with baseline p95 13.903ms, load p95 8.052ms and delta -5.851ms.

Because the two Fabric observations conflict strongly on GitHub-hosted shared runners, A11 remains PARTIAL rather than being promoted to PASS. A fixed dedicated performance environment should repeat the exact load shape before the <=5ms product target is considered closed.

Fabric jobs:
- first attempt: job `107974177099` — FAIL on A11 performance only
- unchanged retry: job `107975254865` — PASS

The deterministic A11 queue-bound checks pass independently of this live performance target.

### 4. A09 / A10 external model live

A dedicated temporary workflow attempted live verification without exposing secret values.

Workflow:

- name: `T10 Model Live TEMP`
- run: #1
- run id: `36104376507`
- result: workflow SUCCESS, provider tests SKIPPED because credentials were absent

Credential availability recorded by the workflow:

- OpenAI configured: **no**
- TypeSafe configured: **no**

Therefore:

| Acceptance | Status | Reason |
|---|---|---|
| A09 | UNVERIFIED | `TYPESAFE_API_KEY` unavailable |
| A10 | UNVERIFIED | `OPENAI_API_KEY` and `TYPESAFE_API_KEY` unavailable |

The existing assets remain ready:

- `evals/t05/jev-korean-cases.jsonl`: 200 labeled Korean examples
- `evals/t05/run-jev-eval.mjs`: real Jev evaluation with dev-only threshold selection and holdout reporting
- `evals/t05/live-models.mjs`: real Jev + GPT-6 Luna Tool-call/result smoke

No mock result is reported as live provider evidence.

## Acceptance matrix

| ID | Status | Notes |
|---|---|---|
| A01 | PASS | deterministic + all three live platforms |
| A02 | PASS | deterministic + live deop/re-op on all three platforms |
| A03 | PASS | deterministic TTL; live follow-up/end/public escape on all three |
| A04 | PASS | deterministic server/UUID/session isolation |
| A05 | PASS | real location comparison and real self-teleport on all three |
| A06 | PASS | no arbitrary command Tool path; live and deterministic |
| A07 | PASS | reconnect and no automatic uncertain state-change retry |
| A08 | PASS | deterministic Jev/Luna failure policy |
| A09 | UNVERIFIED | real TypeSafe credential unavailable |
| A10 | UNVERIFIED | real OpenAI + TypeSafe credentials unavailable |
| A11 | PARTIAL | queue bounds pass; Paper/NeoForge live perf pass; Fabric first attempt failed and unchanged retry passed, so dedicated-host confirmation is still required |
| A12 | PASS | provider Tools absent when providers absent |
| A13 | PASS | concrete T09 audit failure is fail-closed |
| A14 | N/A | v0.2 only |

## Important implementation gap discovered by T10

The protocol states that Brain opens a loopback WebSocket server and the Adapter connects to it.

The product repository currently contains:

- Adapter outbound WebSocket clients;
- Brain Core;
- AI integration;
- operations/audit runtime;

but it does **not** yet contain the production Brain WebSocket listener/handshake/orchestration entrypoint that composes those pieces into a runnable daemon.

The T10 live suite therefore implements an authenticated test-only gateway at:

`tests/acceptance/live/gateway.mjs`

This gateway is appropriate acceptance scaffolding and proved the real platform Adapter wire behavior, but it must not be presented as the production Brain transport implementation.

A production release still needs that daemon entrypoint before v0.1 can be considered deployable.

## Reproduction

Deterministic acceptance:

```bash
cd brain
npm ci
npm run build
cd ../tests/acceptance
npm install --ignore-scripts --no-audit --no-fund
npm run test:core
```

Live platform scenarios:

```bash
cd tests/acceptance
npm run live:paper
npm run live:fabric
npm run live:neoforge
```

Live model verification, once credentials are deliberately supplied:

```bash
cd brain
npm ci
npm run build
cd ..
TYPESAFE_API_KEY=... node evals/t05/run-jev-eval.mjs --output /tmp/jev-report.json
OPENAI_API_KEY=... TYPESAFE_API_KEY=... node evals/t05/live-models.mjs
```

Do not commit credentials or generated provider-secret files.

## Release conclusion

The v0.1 platform adapters and deterministic security/policy boundaries have substantially stronger evidence than before T10, including actual Paper/Fabric/NeoForge servers and actual Minecraft protocol clients.

The release gate is intentionally **not marked complete** until:

1. A09 real Jev evaluation is run and the holdout target is evaluated;
2. A10 real Luna/Jev Tool-call evidence is captured;
3. the mixed Fabric A11 performance observations are resolved under a dedicated fixed-load environment;
4. a production Brain WebSocket server/daemon entrypoint exists and is included in end-to-end verification.
