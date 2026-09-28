# Repository Working Guidelines

## Project and Documentation Baseline

- This repository implements JARVIS for Minecraft servers. It maintains the server platform adapters (Paper, Fabric, NeoForge), the shared Java Embedded Brain/runtime module, and retained language-neutral protocol compatibility assets together.
- Use [`docs/architecture.md`](docs/architecture.md) as the source of truth for the current architecture and platform responsibilities, and [`docs/JarvisMinecraft_Phase1-8_Test_Guide.md`](docs/JarvisMinecraft_Phase1-8_Test_Guide.md) for the Phase 1–8 verification sequence and release gates. Follow [`docs/protocol.md`](docs/protocol.md) for message envelopes and connection rules, [`docs/tools.md`](docs/tools.md) for Tool inputs/results/ranges, and [`docs/compatibility.md`](docs/compatibility.md) plus [`docs/build.md`](docs/build.md) for version and build baselines. Do not duplicate those contracts here; update the authoritative document instead.
- Before making changes, inspect the current branch, worktree, and changed files. Preserve existing staged, modified, and untracked files, and modify only the paths required by the request.
- Do not record rapidly changing state such as the current commit SHA, branch progress, release-gate status, or recent test results in `AGENTS.md`. Store that evidence as dated/task-specific snapshots under `docs/verification/`, and keep architecture documents focused on the current structure.
- If a local snapshot and remote progress differ, do not treat stale tracking refs as the current remote state. Find the latest worktree/evidence intended by the user, and do not recreate existing implementation/tests or reset a checkout arbitrarily. When modifying remote-only source that is not present in the local checkout, first confirm the checkout scope for the current task.

## Technical and Module Boundaries

- The current pinned baseline is Minecraft 1.21.8, Java 21, Gradle Wrapper 8.14.5, and Node.js 24. When changing versions, review the relevant decision documents and lock files together with compatibility evidence.
- The shared Java module must not import Bukkit/Paper, Fabric, NeoForge, or NMS APIs. Do not pass Minecraft platform objects across Brain or transport boundaries; convert them to bounded DTO snapshots.
- Platform-specific events, permission checks, scheduler integration, and official server API calls belong to each Adapter. Do not directly depend on another platform Adapter implementation.
- The Brain composes sessions, request budgets, classification, response generation, and Tool proposals. The Adapter uses current server state and authority to re-check capabilities and Tools and make the final execution decision.
- If the protocol shape changes, update JSON Schema, valid/invalid fixtures, the manifest, protocol documentation, and consumers (Java/TypeScript) together. Do not loosen the wire contract or fixture semantics merely for implementation convenience.

## Security and Runtime Rules

- In v0.1, an interactive actor must be currently online and recognized as an OP by the server. Brain or model payloads are not authority evidence. Re-check server authority at admission, Tool execution, and result-delivery boundaries.
- Treat model output as an untrusted proposal. Process requests only after they pass the registered Tool allowlist, capability checks, strict argument validation, and range limits.
- Do not add arbitrary console commands, SQL, code execution, arbitrary file access, forced movement of other players, ban/warn actions, or rollback. Scope expansion must first be approved in the architecture document and the relevant protocol/tool contracts.
- Do not wait for AI/network/disk/DB I/O on a Minecraft tick or server thread. Call Minecraft APIs only from the execution context required by the platform. Re-check the player's OP and online state before delivering asynchronous results.
- Keep Provider credentials outside persona/knowledge content and operational logs. Paper may resolve Provider credentials from system property, environment, then plugin config fallback; Fabric/NeoForge use system property or environment. There is no production WebSocket shared-secret path. Do not put secrets, full prompts, or unnecessary personal information in logs or model input.
- If Jev fails or is uncertain, do not expose mutation Tools. If Luna fails, do not invent facts; use the defined failure path. If the outcome of a mutation request is uncertain, return `OUTCOME_UNKNOWN` and do not retry automatically.
- Treat external Paper Providers as optional features that require both public APIs and verified runtime capabilities. Do not enable a Tool merely because a plugin is installed.
- Treat `persona.md` and `knowledge/*.md` as bounded contextual input only. They must never grant Tool authority, change `ExecutionPolicy`, override current server/OP authority, weaken audit/deadline/scheduling rules, or be written verbatim to operational logs. `knowledge/README.md` is operator guidance and is not model context.
- Treat `conversations/*.jsonl` as explicit opt-in retained user data, not as operational logs. Never archive Tool results/arguments, hidden prompts/policy, reasoning, Provider credentials, persona/knowledge contents, or ACTIVE proactive ambient-chat context. Optional conversation-memory retrieval must remain same-server/same-requester, previous-session only, bounded by file/turn/byte/lookback limits, fail-open, and lower priority than Core Policy/current Tool evidence. Retrieved history must never widen Tool authority or make a past request current permission.

## Changes and Verification

- Use English by default in documentation and code comments. Preserve Java/Kotlin/TypeScript identifiers, protocol fields, Tool names, and model/SDK versions exactly as defined.
- Update existing CI and verification scripts when the contract changes. If the user did not explicitly request tests or verification, do not run tests, builds, servers, or paid-model calls. Never report an unrun verification as passed.
- In status reports, distinguish documentation/static analysis, builds, server startup, platform UI, external-model calls, and real E2E results. Tie each conclusion to actual evidence from the current checkout.
- External publication, publish, push, merge, paid API calls, server restarts, or other actions that affect systems outside the repository must stay within the user's explicit request scope.

## Local Paper Test Server

- Follow [`test-server/README.md`](test-server/README.md) for server startup and manual scenarios. Server files, worlds, configuration, and logs live under `test-server/paper/` and are local user data. Do not delete, reset, or commit them unless explicitly requested.
- The authoritative file for plugin configuration and Provider credentials is `test-server/paper/plugins/JarvisMinecraft/config.yml`. The Paper Adapter resolves values in this order: Java system property, process environment variable, then this configuration file. Do not inject root `config/.env.local` values into the server process or copy keys into other files. Never print key values in command output, logs, or documentation.
- Build Paper artifacts using the repository's Java 21 and Gradle Wrapper baseline. The Paper-only artifact is generated at `minecraft/paper/build/libs/jarvisminecraft-paper.jar`. Verify that the server is stopped before replacing `test-server/paper/plugins/jarvisminecraft-paper.jar`.
- Start the server from the repository root with `.\test-server\paper\start-server.ps1`. The script verifies Java 21, EULA acceptance, and disabled RCON. The default address is `localhost:25565`; stop the server by entering `stop` in the server console.
- Verify startup separately through JARVIS plugin activation in the logs, Paper boot completion, and connectivity to `localhost:25565`. Run real Provider requests or paid-model calls only when the user explicitly requests them.
