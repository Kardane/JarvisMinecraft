# JARVIS operations guide

## Scope

JARVIS now runs the Brain inside the Minecraft server JVM. There is no Node.js Brain daemon, WebSocket listener, shared-secret handshake, or reconnect process to operate.

## Required secrets

Supply these to the Minecraft server process through your normal secret store:

- `OPENAI_API_KEY`
- `TYPESAFE_API_KEY`

Optional:

- `JARVIS_SERVER_ID` / `jarvis.serverId` — explicit logical server ID override

When no override is supplied, JARVIS creates a stable local ID once and reuses it on later starts:

- Paper: `plugins/JarvisMinecraft/server-id.txt`
- Fabric / NeoForge: `config/jarvisminecraft/server-id.txt`

Reference values are shown in `config/jarvis.env.example`.

Do not log provider keys, raw process environments, or complete configuration objects.

## Platform configuration

### Paper

Paper's generated `config.yml` contains only:

- `openai-api-key`
- `typesafe-api-key`

The server ID is generated automatically unless the optional system-property/environment override is supplied.

System properties/environment take precedence over plugin-config credentials:

- `jarvis.openaiApiKey` / `OPENAI_API_KEY`
- `jarvis.typesafeApiKey` / `TYPESAFE_API_KEY`

Audit directory:

`plugins/JarvisMinecraft/audit`

### Fabric / NeoForge

Use system properties or environment:

- `jarvis.serverId` / `JARVIS_SERVER_ID`
- `jarvis.openaiApiKey` / `OPENAI_API_KEY`
- `jarvis.typesafeApiKey` / `TYPESAFE_API_KEY`

Audit directory:

`config/jarvisminecraft/audit`

## Startup

At platform startup JARVIS:

1. validates server ID and provider credentials;
2. builds the platform Tool registry;
3. activates optional Paper Providers only when their dependencies/API discovery succeed;
4. constructs `CommonRuntime`;
5. constructs `ChatSessionManager`;
6. constructs `EmbeddedBrainGateway` and `EmbeddedBrain`;
7. constructs the Jev HTTP classifier, Luna client, AI scheduler and JSONL audit sink;
8. starts accepting OP chat requests.

Configuration failure disables/stops JARVIS startup rather than falling back to a weaker policy.

There is no separate Brain startup order.

## Request execution

```text
ChatSessionManager
  -> EmbeddedBrainGateway
  -> AiRequestScheduler
  -> Jev
  -> DeterministicRoutePolicy
  -> Luna
  -> AuditSink
  -> CommonRuntime.ExecutionRuntime
  -> Platform Tool
```

The Minecraft server remains the authority for OP status and server state.

## Audit log

The Java `AsyncJsonlAuditSink` writes bounded JSONL audit files. Its policy remains:

- 7-day retention
- 100 MiB total cap
- 8 MiB per file
- bounded asynchronous queue
- sensitive-key/value masking

A state-changing Tool is executed only after its pre-execution audit record is successfully persisted. Queue saturation, filesystem failure, or audit write rejection causes the mutation to fail closed.

Post-execution audit failure never triggers a Tool retry.

## Stored audit fields

Audit records are limited to operational metadata such as:

- timestamp
- serverId
- requesterUuid
- requestId
- toolCallId
- actionId when applicable
- Tool/risk
- validated argument summary
- outcome/source/latency
- model ID
- fallback reason

Do not store provider secrets, full hidden reasoning, IP addresses, unrelated private chat, or complete server logs.

## Failure handling

### Jev failure

Jev timeout/error/uncertain or configured low confidence activates the deterministic fallback route. Only currently active read-only Tools are exposed to Luna. State-changing Tools are withheld.

### Luna failure

Return the fixed safe failure response. Do not invent server state or claim a Tool succeeded.

### Audit failure

Read-only work may continue according to policy. State-changing work must fail closed until audit health recovers.

### Tool timeout

Read-only Tool timeout is reported as timeout/error according to the runtime contract.

For state-changing Tools, a deadline expiry after execution may have started is `OUTCOME_UNKNOWN`. Do not automatically replay the action.

### deop/logout/session end

Invalidate the actor/session and cancel queued work. Before Tool execution and before reply delivery, current online OP/session state is checked again.

## Shutdown

Platform shutdown calls `BrainGateway.stop()`, which:

1. stops accepting new Embedded Brain work;
2. shuts down the bounded AI scheduler;
3. clears active Luna request state;
4. closes owned Luna/audit resources;
5. rejects late delivery after the gateway is stopped.

There is no external Brain process to signal.

## Build verification

Deterministic verification:

```bash
./gradlew build
```

This includes the E12 Embedded policy parity/safety verification, T06/T07/T08 platform contract tests, existing Provider tests, and E16 deployable-artifact content verification.

CI also boots a clean Paper, Fabric, and NeoForge server with each packaged artifact and an E16 smoke flag. The smoke exits before provider credentials are required; normal production startup still requires both provider keys.

Live provider verification requires real credentials:

```bash
OPENAI_API_KEY=... TYPESAFE_API_KEY=... \
  ./gradlew :minecraft:common:embeddedBrainLiveVerification
```

Never commit credentials or generated environment files.

## Historical Remote artifacts

The old protocol schema/fixtures and `tests/acceptance/out` results are retained as historical compatibility/evidence assets. They do not describe an active WebSocket service after E14.

Node-based Brain and acceptance runners were removed. New runtime verification should target the Embedded Java path.
