# JARVIS operations guide

## Scope

This document describes the v0.1 Brain operations boundary. Minecraft adapters do not store OpenAI or TypeSafe credentials.

## Required environment

Copy `config/brain.env.example` into your process manager or secret store and replace all placeholders.

Required secrets:

- `OPENAI_API_KEY`
- `TYPESAFE_API_KEY`
- `JARVIS_SHARED_SECRET`

The Brain listener is restricted to loopback in v0.1. `JARVIS_BRAIN_HOST` accepts only `127.0.0.1`, `::1`, or `localhost`.

The fixed v0.1 policy is:

- OP-only access: enabled
- responses: requester-only
- warning action: disabled
- rollback action: disabled

There is no configuration switch that disables OP-only enforcement in v0.1.

## Audit log

The Brain audit sink implements the T04 `AuditPort`.

Default storage:

- directory: `./logs/jarvis-audit`
- JSON Lines
- 7-day retention
- 100 MiB total cap
- 8 MiB per-file rotation
- 512-record asynchronous queue

Files are named:

`jarvis-audit-YYYY-MM-DD-NNNN.jsonl`

Every accepted record is appended with asynchronous filesystem I/O. `record()` returns `true` only after the append succeeds.

For a state-changing Tool, Brain Core awaits the pre-execution audit call. If the audit queue is full, the filesystem append fails, or the record cannot fit under the configured storage limits, `record()` returns `false` and the Tool is not executed.

Post-execution audit failure never causes an automatic Tool retry because the Tool may already have changed server state.

## Stored fields

The audit record contains only the T04 audit contract:

- timestamp
- serverId
- requesterUuid
- requestId
- toolCallId
- actionId when applicable
- Tool
- risk
- validated argument summary
- outcome
- source
- latency
- model ID
- fallback reason
- advertised capability descriptors

The audit sink does not receive or store the full user prompt, private chat history, IP address, provider API keys, Adapter shared secret, or model hidden reasoning.

Sensitive keys such as `secret`, `token`, `password`, `authorization`, and API-key fields are redacted again at the sink boundary. Known provider-key and bearer-token patterns inside string values are also redacted.

## Health

`OpsRuntime.health()` exposes:

- overall `HEALTHY / DEGRADED / UNHEALTHY`
- whether audit storage is currently writable
- queue depth and capacity
- rejected record count
- last successful write time
- last error time/code
- current managed audit byte/file counts
- provider-key configured booleans
- fixed policy values

It never returns secret values.

A disk write failure marks audit health `UNHEALTHY`. Future records may retry the filesystem; a later successful append restores writable health.

Queue saturation is surfaced as `AUDIT_QUEUE_FULL`. It is never silently dropped.

## Startup

1. Validate provider keys and the Adapter shared secret.
2. Validate loopback bind host/port.
3. Construct `OpsRuntime`.
4. Inject `ops.audit` into `BrainCore`.
5. Start the Brain transport only after configuration succeeds.

Example TypeScript wiring:

```ts
const ops = OpsRuntime.fromEnvironment(process.env);
const core = new BrainCore({
  model,
  audit: ops.audit,
});
```

Do not log the raw configuration object because it contains provider credentials.

## Shutdown

On SIGTERM/SIGINT:

1. stop accepting new chats/connections;
2. await `ops.close()` so queued audit entries drain;
3. close Adapter transports;
4. exit.

Once closing starts, new audit records are rejected.

## Failure handling

### Provider outage

OpenAI/TypeSafe failures are handled by the T05 model policy. Do not insert an unapproved substitute model.

### Audit disk failure

Health becomes unhealthy. Read-only operations may continue according to Core policy. State-changing Tools are rejected because pre-execution audit confirmation fails.

Correct the disk/path/permission problem and verify health before retrying an action. Do not bypass the audit gate.

### Queue saturation

The record that cannot be queued returns failure. Reduce request pressure or fix slow storage. Increasing the queue changes memory/backpressure behavior and should be capacity-tested.

### Retention/rotation failure

A deletion or metadata error is treated as an audit I/O failure. This prevents silently exceeding the configured audit policy for subsequent state-changing actions.

## Data sent to external model providers

OpenAI receives the current operator request context needed for the answer, active Tool schemas, and bounded Tool results. TypeSafe receives the latest message, a short non-Tool topic summary, and capability names.

By default JARVIS does not send IP addresses, provider secrets, Adapter shared secrets, complete server logs, or unrelated private chats.

## Verification

T09 tests cover configuration rejection, secret masking, JSONL rotation/retention/total-cap behavior, queue saturation, disk failure, health, close/drain, and Brain Core fail-closed behavior for a state-changing Tool.

T10 records platform/client/model live evidence separately; passing T09 mock/contract tests is not reported as external-model or Minecraft-client E2E.
