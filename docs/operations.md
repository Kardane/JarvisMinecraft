# JARVIS operations guide

## Scope

JARVIS now runs the Brain inside the Minecraft server JVM. There is no Node.js Brain daemon, WebSocket listener, shared-secret handshake, or reconnect process to operate.

JARVIS interaction access is configurable through `OP / WHITELIST / ALL / BLACKLIST`; the default remains `OP`. Accepted invocations, follow-up messages, session notices, and replies remain visible to all online players in server chat. Minecraft Tool access remains OP-only even when a broader interaction audience is configured.

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

The runtime-policy snapshot is deliberately separate from provider
credentials. Phase 2 applies the interaction subset at runtime:

- `jarvis.interaction.wake-words`
- `jarvis.interaction.follow-up-seconds`
- `jarvis.interaction.audience.*`

`PASSIVE` is the default. `ACTIVE` currently behaves like PASSIVE for direct
invocations and active-session follow-ups; proactive ambient-chat initiation is
not implemented yet.

Phase 3 applies `jarvis.model.reasoning.*`:

- `mode=AUTO`: use Jev's validated reasoning choice for the request;
- `mode=NONE/LOW/MEDIUM/HIGH`: force that effort regardless of Jev;
- if Jev fails while mode is `AUTO`, use `fallback`.

The resolved effort remains fixed across all Luna rounds in that request.

Phase 4 applies `jarvis.response.*`:

- `prefix`: AI/error/progress prefix. Legacy `&` formatting is parsed only
  here, never from model output.
- `waiting-message.enabled/threshold-ms/messages`: show at most one delayed
  public progress message while a request is still running.
- `sound.enabled/id/volume/pitch`: after a final/error response, play the
  configured sound only to the requester.

Session-start/end notices still use the legacy platform notice path.

Phase 5 applies `jarvis.execution.*`:

- `READ_TALK`: active read-only Tools only.
- `EXECUTE_LITE`: read-only Tools plus LOW-risk state-changing Tools explicitly listed in `lite.allow-tools`.
- `EXECUTE`: read-only Tools plus state-changing Tools explicitly listed in `full.allow-tools`.
- `deny-tools` takes precedence and may hide read-only Tools too.
- execution actor remains `OP`; broader chat audience does not grant Tool authority.

Tool names are exact wire names such as `teleport_staff`. Unknown names,
read-only entries in allow lists, and non-LOW entries in the LITE allow list
fail configuration validation.

Scheduling and `/jm reload` remain pending later phases.

A failed initial runtime-policy parse stops JARVIS startup. `ConfigManager`
already provides fail-safe snapshot replacement semantics for the later reload
command: an invalid replacement does not overwrite the previous valid snapshot.

### Paper

Paper's generated `config.yml` contains the existing provider-key
fallbacks plus the non-secret `jarvis.*` runtime-policy tree.

Provider-key fallback fields:

- `openai-api-key`
- `typesafe-api-key`

Runtime-policy groups:

- `jarvis.interaction.*`
- `jarvis.model.*`
- `jarvis.response.*`
- `jarvis.execution.*`
- `jarvis.scheduling.*`

The server ID is generated automatically unless the optional
system-property/environment override is supplied.

System properties/environment take precedence over plugin-config credentials:

- `jarvis.openaiApiKey` / `OPENAI_API_KEY`
- `jarvis.typesafeApiKey` / `TYPESAFE_API_KEY`

Audit directory:

`plugins/JarvisMinecraft/audit`

### Fabric / NeoForge

Use system properties or environment for identity/provider credentials:

- `jarvis.serverId` / `JARVIS_SERVER_ID`
- `jarvis.openaiApiKey` / `OPENAI_API_KEY`
- `jarvis.typesafeApiKey` / `TYPESAFE_API_KEY`

Optional runtime-policy file:

`config/jarvisminecraft/jarvis.properties`

If the file does not exist, the validated built-in defaults are used. A
reference file is committed as `config/jarvis.properties.example`. List
values in the properties format use `|` as the separator.

Audit directory:

`config/jarvisminecraft/audit`

## Startup

At platform startup JARVIS:

1. loads and validates the Phase 1 runtime-policy snapshot;
2. validates server ID and provider credentials;
3. builds the platform Tool registry;
4. activates optional Paper Providers only when their dependencies/API discovery succeed;
5. constructs `CommonRuntime`;
6. constructs `ChatSessionManager`;
7. constructs `EmbeddedBrainGateway` and `EmbeddedBrain`;
8. constructs the Jev HTTP classifier, Luna client, AI scheduler and JSONL audit sink;
9. starts accepting chat requests from players allowed by the configured audience.

Configuration failure disables/stops JARVIS startup rather than falling back to a weaker policy.

There is no separate Brain startup order.

## Request execution

```text
InteractionCoordinator
  -> ChatSessionManager
  -> EmbeddedBrainGateway
  -> AiRequestScheduler
  -> ExecutionPolicy
  -> Jev (engagement + route + reasoning)
  -> DeterministicRoutePolicy
  -> ReasoningPolicy
  -> Luna
  -> ProgressNotifier / StyledChatMessage
  -> AuditSink
  -> CommonRuntime.ExecutionRuntime
  -> Platform Tool
```

The Minecraft server remains the authority for online identity, OP status, and
server state. `AudiencePolicy` decides who may converse; current OP status
still decides whether a request receives Minecraft Tool schemas.

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

Jev timeout/error/invalid output/model mismatch activates the deterministic
fallback route. Only currently active read-only Tools are exposed to Luna and,
when reasoning mode is `AUTO`, the configured reasoning fallback is used.

A valid `UNCERTAIN` route or configured low-confidence abstention also keeps
the deterministic read-only fallback route. A valid Jev reasoning choice may
still be used for that request.

Jev engagement is recorded in `JevClassification`, but Phase 3 does not use
`IGNORE` or `START_CONVERSATION` to alter already-admitted DIRECT/FOLLOW_UP
requests.

### Luna failure

Return the fixed safe failure response with the configured response prefix.
Do not invent server state or claim a Tool succeeded. If response sound is
enabled, requester-only sound feedback applies to this response too.

### Progress feedback

Progress is scheduled only after the configured threshold. Completion marks the
progress handle first. Before actual public delivery, current interaction
authorization, active session, and completion state are checked again. Progress
feedback does not play the final-response sound.

### Execution policy change

An in-flight request keeps the Tool ceiling established when it began, so a
later policy relaxation cannot add new mutation Tools to that request. Policy
tightening is applied before each Luna round and immediately before execution.
A state-changing Tool is checked before pre-audit and again after the audit
record succeeds, before handoff to `CommonRuntime`.

### Audit failure

Read-only work may continue according to policy. State-changing work must fail closed until audit health recovers.

### Tool timeout

Read-only Tool timeout is reported as timeout/error according to the runtime contract.

For state-changing Tools, a deadline expiry after execution may have started is `OUTCOME_UNKNOWN`. Do not automatically replay the action.

### audience revoke / deop / logout / session end

Invalidate the actor/session and cancel queued work. Before reply delivery,
current online identity, audience authorization, and session state are checked
again. Tool execution independently rechecks current OP authority.

The existing internal `OP_REVOKED` cancel reason is reused when an active
session loses audience authorization; the protocol enum is not expanded in
Phase 2.

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

This includes the Phase 1 `JarvisConfig` validation/fail-safe reload
verification, E12 Embedded policy parity/safety verification, T06/T07/T08
platform contract tests, existing Provider tests, and E16 deployable-artifact
content verification.

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
