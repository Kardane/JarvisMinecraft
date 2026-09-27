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

The runtime-policy snapshot and operator prompt content are deliberately separate from provider credentials. Phase 2 applies the interaction subset at runtime:

- `jarvis.interaction.wake-words`
- `jarvis.interaction.follow-up-seconds`
- `jarvis.interaction.audience.*`

`PASSIVE` remains the default. In `ACTIVE`, wake-word and follow-up behavior
is unchanged, and allowed public chat is additionally observed for proactive
engagement. Public chat is never suppressed by this observation.

ACTIVE proactive behavior:

- retain at most 50 ambient messages internally; pass only
  `proactive.context-messages` to a decision;
- only one proactive Jev request is in flight at a time;
- classification starts no more than once per second;
- require `START_CONVERSATION` and
  `proactive.confidence-threshold`;
- after a proactive session starts, enforce the configured server-wide
  `proactive.cooldown-seconds`;
- recheck ACTIVE mode, audience authorization, requester session state, and
  cooldown on the server thread before starting the session;
- Jev failure/IGNORE/low confidence produces no JARVIS message.

Phase 3 applies `jarvis.model.reasoning.*`:

- `mode=AUTO`: use Jev's validated reasoning choice for the request;
- `mode=NONE/LOW/MEDIUM/HIGH`: force that effort regardless of Jev;
- if Jev fails while mode is `AUTO`, use `fallback`.

The resolved effort remains fixed across all Luna rounds in that request.

Response configuration under `jarvis.response.*` includes:

- `prefix`: AI/error/progress prefix. Legacy `&` formatting and
  `<#RRGGBB>` hex colors are parsed only here, never from model output.
- `waiting-message.enabled/threshold-ms/messages`: show at most one delayed
  public progress message while a request is still running. The built-in
  default now provides 16 message variations.
- `sound.enabled/id/volume/pitch`: after a final/error response, play the
  configured sound only to the requester.
- `metrics.enabled/icon`: append only the configured icon to final/error
  replies. Hovering the icon shows aggregate Luna input/output/total token
  usage when available plus end-to-end request processing time.

Starting a follow-up session no longer emits the old public "120 seconds"
session-rules announcement. The configured follow-up TTL itself is unchanged.

Phase 5 applies `jarvis.execution.*`:

- `READ_TALK`: active read-only Tools only.
- `EXECUTE_LITE`: read-only Tools plus LOW-risk state-changing Tools explicitly listed in `lite.allow-tools`.
- `EXECUTE`: read-only Tools plus state-changing Tools explicitly listed in `full.allow-tools`.
- `deny-tools` takes precedence and may hide read-only Tools too.
- execution actor remains `OP`; broader chat audience does not grant Tool authority.

Tool names are exact wire names. Current LOW-risk mutation names are:

- `teleport_staff`
- `weather_set`
- `time_set`

Unknown names, read-only entries in allow lists, and non-LOW entries in the
LITE allow list fail configuration validation.

Example:

```yaml
jarvis:
  execution:
    mode: EXECUTE_LITE
    actors: OP
    lite:
      allow-tools:
        - weather_set
        - time_set
      deny-tools: []
```

The default remains `READ_TALK`, so merely upgrading to Phase 6 does not
activate mutations.

Phase 7 applies `jarvis.scheduling.*`:

- `enabled=false` keeps `schedule_action` hidden.
- `max-delay-seconds` is validated in the range 1..60.
- `max-duration-seconds` is validated in the range 1..60.
- schedulable mutations are limited to `teleport_staff`, `weather_set`,
  and `time_set`.
- one-shot schedules use a delay only; repeating schedules use delay +
  interval + duration.
- each execution rechecks current scheduling/execution policy, active Tool
  registration, audit health, and current online OP authority.
- schedules are not persisted across server restart.

`cancel_scheduled_action` can cancel only a pending schedule owned by the
requesting player. Actor invalidation/logout and Brain shutdown also cancel
that actor's pending schedules.

Persona/knowledge configuration:

- `jarvis.personality.enabled`
- `jarvis.knowledge.enabled`
- `jarvis.knowledge.max-files`
- `jarvis.knowledge.max-file-bytes`
- `jarvis.knowledge.max-total-bytes`

Default prompt-content limits are 32 knowledge files, 32 KiB per knowledge file,
and 128 KiB total knowledge. `persona.md` is limited to 32 KiB.

A failed initial runtime-policy or enabled prompt-content validation stops JARVIS
startup. Production runtimes share one `RuntimeConfigurationManager` composite
snapshot across interaction admission, Brain policy, persona/knowledge context,
response/status/logging paths. Invalid reload candidates never partially replace the
active config or prompt content.

### Paper

Paper's generated `config.yml` contains the existing provider-key
fallbacks plus the non-secret `jarvis.*` runtime-policy tree.

Provider-key fallback fields:

- `openai-api-key`
- `typesafe-api-key`

Runtime-policy groups:

- `jarvis.interaction.*`
- `jarvis.model.*`
- `jarvis.personality.*`
- `jarvis.knowledge.*`
- `jarvis.response.*`
- `jarvis.execution.*`
- `jarvis.scheduling.*`
- `jarvis.logging.*`

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

## Persona and server knowledge

Platform locations:

```text
Paper
plugins/JarvisMinecraft/
├─ config.yml
├─ persona.md
└─ knowledge/
   ├─ README.md
   └─ *.md

Fabric / NeoForge
config/jarvisminecraft/
├─ jarvis.properties
├─ persona.md
└─ knowledge/
   ├─ README.md
   └─ *.md
```

On first startup JARVIS creates `persona.md` and `knowledge/README.md` only
when they are missing. Existing files are never overwritten. The knowledge README is
operator guidance and is not sent to the model.

`persona.md` controls conversational style only. Files under `knowledge/*.md`
provide bounded server-specific reference context such as rules, locations, ranks,
lore, and services. Do not store API keys, passwords, private player data, or other
secrets in these files. Persona/knowledge content is runtime context, not model
training and not persistent model memory.

Knowledge loading is non-recursive and UTF-8 only. Non-Markdown files,
subdirectories, and `knowledge/README.md` are ignored. Symlink/path escapes outside
the platform JARVIS directory are rejected. Knowledge files are ordered
deterministically by normalized filename.

## Generated Tool reference

Every platform asynchronously generates a current Tool catalog in its JARVIS
configuration directory:

```text
tools.md
```

The file lists every known Tool wire name with its runtime source, capability,
risk, and state-changing flag. Registered optional-provider Tools are reflected
from the actual startup registry. Scheduling control Tools are marked
`brain-control`. The file is generated operational reference material and may
be overwritten on each server startup.

A Tool appearing as registered does not grant authority. Actual exposure still
depends on current OP authority, Jev route, execution policy, scheduling policy,
and provider availability.

## Startup

At platform startup JARVIS:

1. creates missing prompt-content templates without overwriting existing files;
2. loads and validates one atomic runtime snapshot containing structured config plus enabled persona/knowledge;
3. validates server ID and provider credentials;
4. builds the platform Tool registry;
5. activates optional Paper Providers only when their dependencies/API discovery succeed;
6. constructs `CommonRuntime`;
7. constructs `ChatSessionManager`;
8. constructs `EmbeddedBrainGateway` and `EmbeddedBrain`;
9. constructs the Jev HTTP classifier, Luna client, AI scheduler and JSONL audit sink;
10. starts accepting chat requests from players allowed by the configured audience.

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
  -> SchedulingPolicy / ScheduledActionService
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

## Operational logging

Operational console logging is separate from the JSONL Audit.

Default categories cover request lifecycle, Jev/Luna, Tool lifecycle, scheduling, ACTIVE proactive decisions, and Audit health transitions. Raw player chat, raw prompts/responses, provider keys, Authorization headers, and complete environment/config dumps are not operational log fields. Response hover metrics expose token counts and duration only; they do not expose prompts, reasoning, Tool arguments, or secrets.

Common settings:

- `jarvis.logging.level`
- `jarvis.logging.console.enabled`
- `jarvis.logging.request.lifecycle`
- `jarvis.logging.ai.jev`
- `jarvis.logging.ai.luna`
- `jarvis.logging.tool.lifecycle`
- `jarvis.logging.proactive.decisions`
- `jarvis.logging.health.interval-seconds`

Audit health polling logs only state/error-code changes:

- `audit.degraded`
- `audit.unhealthy`
- `audit.recovered`

Operational logger failure is fail-open and must not change Tool semantics. Audit failure remains fail-closed for state-changing Tools.

## Status command

Paper/Fabric/NeoForge expose OP-only:

```text
/jm status
/jm reload
```

The status summary includes runtime state, interaction/audience/execution mode, scheduling state, AI queue/active counts, proactive in-flight state, and Audit health/queue/file summary. It never prints secrets or raw AI/chat content.

`/jm reload` asynchronously reloads structured config, `persona.md`, and
`knowledge/*.md`. Disk I/O runs on the dedicated `jarvis-config-reload`
executor, not the Minecraft server thread. At most one reload runs at a time.
On success the complete new runtime snapshot is published. On any parse, UTF-8,
path, size, or content-loading failure the complete previous snapshot remains active.

Success:

```text
[JARVIS] Configuration reloaded.
```

Failure:

```text
[JARVIS] Reload failed; previous configuration remains active.
```

Player-visible output does not contain prompt content, secrets, raw external paths, or
stack traces. Operational success logs contain only configuration summary and prompt
document/byte counts. `/jm test` is not implemented yet.

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

### Phase 6 structured actions

`weather_set` and `time_set` target only already loaded worlds. They use
Paper/Fabric/NeoForge world APIs directly and never dispatch a console command.

`weather_set` accepts `CLEAR / RAIN / THUNDER` and 1~3600 seconds.
`time_set` accepts time-of-day 0~23999 and preserves the current day count.
Both require a non-null actionId, successful pre-execution audit, current
execution-policy allowance, and current online OP authority.

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

This includes `JarvisConfig` atomic reload verification,
`promptContentVerification` for persona/knowledge loader/template boundaries,
E12 Embedded policy parity/safety verification, T06/T07/T08
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


## ACTIVE-mode operational notes

ACTIVE mode can generate more Jev traffic than PASSIVE because ordinary allowed
public chat may become a proactive candidate. Runtime pressure is bounded by one
in-flight proactive classifier and a one-second classification interval.

Proactive context is memory-only and not persisted. Actor invalidation removes
that actor's retained ambient entries. A proactive response creates the same
requester-scoped follow-up session used by direct invocation.

For safety, proactive requests cannot receive state-changing Tools, including
`teleport_staff`, `weather_set`, `time_set`, `schedule_action`, or
`cancel_scheduled_action`. This remains true even when execution mode is
`EXECUTE`.
