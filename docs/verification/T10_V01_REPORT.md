# T10 v0.1 acceptance report

Date: 2026-09-25  
Branch: `codex/t10-acceptance`  
Scope: v0.1 acceptance evidence after T03-T09 plus the production Brain daemon integration

## Overall status

**PARTIAL — implementation E2E is green; external-provider and dedicated-performance gates remain open.**

The deterministic policy suite and the real Minecraft platform/client scenarios now pass through the **production `BrainWebSocketServer` transport**. The v0.1 release gate is still not marked complete because:

1. A09 has not been run against the real TypeSafe Jev provider; `TYPESAFE_API_KEY` is not configured in repository Actions.
2. A10 has not been run against the real OpenAI + TypeSafe providers; `OPENAI_API_KEY` and `TYPESAFE_API_KEY` are not configured.
3. A11 passes on all three platforms in the latest production-transport run, but an earlier Fabric run on a GitHub shared runner exceeded the +5ms target and an unchanged retry passed. A dedicated fixed-load host is still required before treating the performance target as closed.

No missing external gate is converted into a PASS.

## Production Brain transport status

The deployability gap previously found by T10 is resolved.

Production daemon integration:

- PR: #12
- merge commit: `5aebf3bb5cbba4624088e455af6cc9913657f087`
- startup: `cd brain && npm ci && npm run build && npm start`
- endpoint: `ws://127.0.0.1:8181/ws`
- authentication: `X-Jarvis-Secret`
- composition:
  - OpsRuntime
  - TypeSafe Jev
  - GPT-6 Luna
  - JarvisAiModel
  - BrainCore
  - BrainWebSocketServer

Production transport unit/integration checks passed 6/6 for authentication, hello/capabilities, Tool round trip, reconnect replacement, cancellation, and serverId binding.

The live acceptance harness no longer implements an independent WebSocket server. `tests/acceptance/live/gateway.mjs` is a thin harness around the production `BrainWebSocketServer` with a deterministic ModelPort and in-memory audit observer.

## Evidence layers

### 1. Deterministic policy / state-machine acceptance

Latest production-transport acceptance run:

- workflow: `T10 Acceptance TEMP`
- run: #10
- run id: `36110556203`
- commit: `457a4fbca6c56671340f4a1ad8861c4f69ce2224`
- deterministic result: **10/10 PASS**

Coverage:

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

### 2. Real Minecraft server + protocol-client acceptance through production transport

Run #10 exercised:

- actual Minecraft 1.21.8 dedicated servers;
- actual packaged platform Adapter JARs;
- the production `BrainWebSocketServer`;
- three real Minecraft protocol sessions through Mineflayer 4.39.0:
  - `AdminA`: OP
  - `NonOp`: non-OP
  - `OtherOp`: second OP
- a deterministic ModelPort so Minecraft/platform behavior can be tested without provider credentials.

These are real protocol clients, not Mojang GUI clients. No GUI-client visual evidence is claimed.

| Platform | Server boot | JARVIS loaded | Production transport | Live checks | Result |
|---|---:|---:|---:|---:|---|
| Paper 1.21.8 | PASS | PASS | PASS | 23/23 | PASS |
| Fabric 1.21.8 | PASS | PASS | PASS | 23/23 | PASS |
| NeoForge 21.8.52 / MC 1.21.8 | PASS | PASS | PASS | 23/23 | PASS |

Each platform recorded 41 accepted Brain chat messages and 42 Tool requests during the full scenario.

Live scenarios verify:

- non-OP JARVIS-looking chat causes zero Brain submissions;
- requester OP receives private JARVIS response;
- non-OP and another OP do not receive the response;
- direct OP chat reaches Brain exactly once;
- follow-up is routed as `FOLLOW_UP`;
- `!내용` bypasses Brain and remains public;
- `대화 끝` ends locally/private and stops forwarding;
- Adapter player location matches the target protocol client's observed position;
- `teleport_staff` actually moves the requesting OP to the target;
- server metrics contain unit and observation timestamp;
- state-changing Tool request has an actionId;
- deop blocks JARVIS intake and re-op can start a new session;
- unregistered command execution remains zero;
- absent CoreProtect/WorldGuard provider Tools are not advertised;
- production Brain transport restart causes Adapter reconnect;
- server remains responsive after reconnect;
- A11 fixed dual-OP load measurement is collected.

Evidence artifacts from run #10:

- Paper: `10852703286`
- Fabric: `10853410459`
- NeoForge: `10852673920`

### 3. A11 live MSPT load measurement

Method:

1. collect eight sequential `get_server_status` MSPT observations;
2. run twelve rounds of two simultaneous OP status requests;
3. collect 24 load MSPT observations;
4. calculate p95 for baseline and load;
5. require `loadP95 - baselineP95 <= 5ms`.

Latest production-transport run #10:

| Platform | Baseline p95 | Load p95 | Delta | Result |
|---|---:|---:|---:|---|
| Paper | 7.466 ms | 4.752 ms | -2.714 ms | PASS |
| Fabric | 17.888 ms | 4.897 ms | -12.991 ms | PASS |
| NeoForge | 16.152 ms | 11.098 ms | -5.053 ms | PASS |

Historical Fabric evidence must still be retained:

| Fabric observation | Baseline p95 | Load p95 | Delta | Result |
|---|---:|---:|---:|---|
| earlier first attempt | 50.375 ms | 92.359 ms | +41.984 ms | FAIL |
| unchanged retry | 13.903 ms | 8.052 ms | -5.851 ms | PASS |
| production-transport run #10 | 17.888 ms | 4.897 ms | -12.991 ms | PASS |

The threshold and calculation were never relaxed. Because the first shared-runner result conflicts sharply with the later two results, A11 remains **PARTIAL** pending repetition on a dedicated fixed-load host. The deterministic queue-bound portion of A11 is PASS.

### 4. A09 / A10 external model live

A temporary provider workflow safely checked credential availability without printing secret values.

- workflow: `T10 Model Live TEMP`
- run id: `36104376507`
- OpenAI configured: **no**
- TypeSafe configured: **no**

Therefore:

| Acceptance | Status | Reason |
|---|---|---|
| A09 | UNVERIFIED | `TYPESAFE_API_KEY` unavailable |
| A10 | UNVERIFIED | `OPENAI_API_KEY` and `TYPESAFE_API_KEY` unavailable |

Ready-to-run real-provider assets:

- `evals/t05/jev-korean-cases.jsonl`: 200 labeled Korean examples
- `evals/t05/run-jev-eval.mjs`: real Jev evaluation with holdout reporting
- `evals/t05/live-models.mjs`: real Jev + GPT-6 Luna Tool-call/result smoke

No deterministic or mocked result is reported as live provider evidence.

## Acceptance matrix

| ID | Status | Notes |
|---|---|---|
| A01 | PASS | deterministic + three production-transport live platforms |
| A02 | PASS | deterministic + live deop/re-op |
| A03 | PASS | deterministic TTL + live follow-up/end/public escape |
| A04 | PASS | deterministic server/UUID/session isolation |
| A05 | PASS | real location comparison and real self-teleport on all three |
| A06 | PASS | arbitrary command Tool path absent |
| A07 | PASS | production transport reconnect + no uncertain mutation retry |
| A08 | PASS | deterministic Jev/Luna failure policy |
| A09 | UNVERIFIED | real TypeSafe credential unavailable |
| A10 | UNVERIFIED | real OpenAI + TypeSafe credentials unavailable |
| A11 | PARTIAL | queue bounds PASS; latest three-platform live perf PASS; historical Fabric shared-runner outlier requires dedicated-host confirmation |
| A12 | PASS | unavailable provider Tools remain unexposed |
| A13 | PASS | concrete T09 audit failure is fail-closed |
| A14 | N/A | v0.2 only |

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

Production Brain process:

```bash
cd brain
npm ci
npm run build
npm start
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

Never commit credentials or credential-bearing environment files.

## Release conclusion

The v0.1 implementation now has passing deterministic policy evidence and passing live Paper/Fabric/NeoForge E2E evidence through the production Brain WebSocket transport.

The release gate remains **PARTIAL**, not complete, until:

1. A09 real Jev 200-case evaluation is run and its holdout target is reported;
2. A10 real GPT-6 Luna + Jev Tool-call/result evidence is captured;
3. A11 is confirmed on a dedicated fixed-load performance host.

The previously identified production Brain transport/daemon gap is closed.
