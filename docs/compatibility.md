# T00 Compatibility and Reuse Survey

Date: 2026-09-24  
Status: **T00 documentation survey complete / T01 may begin; build, real-server, and paid-API live verification not yet performed**

## 1. Scope and Decision Rules

T00 is not an implementation phase. It establishes Minecraft 1.21.8 as the first verification baseline and fixes the **reusable public APIs and compatibility boundaries** for platforms, build tooling, AI SDKs, and Paper Providers.

Status labels:

| Status | Meaning |
|---|---|
| CONFIRMED | Existence and API contract verified from official documentation, official Maven/package repositories, or upstream repositories |
| PINNED | Fixed version to use for the T02 build skeleton. The actual multimodule build had not yet been run at T00 |
| RUNTIME UNVERIFIED | Actual Minecraft server startup/event/plugin combinations had not yet been verified |
| LIVE UNVERIFIED | No real external AI account/API call had yet been made |
| BLOCKED | Feature activation prohibited until licensing, public API, or exact combination is verified |

## 2. Baseline Version Matrix

### 2.1 Shared Runtime / Build

| Item | T00 Pin | Status | Evidence / Notes |
|---|---:|---|---|
| Minecraft | 1.21.8 | PINNED | Work-specification baseline; does not imply binary compatibility with versions after 1.21.8 |
| JVM | Java 21 (64-bit) | PINNED | Official NeoForge 1.21.6–1.21.8 documentation requires Java 21. Paper/Fabric use the same toolchain |
| Gradle Wrapper | 8.14.5 | PINNED | Latest 8.14.x patch in official Gradle Releases. Actual joint build with Loom/ModDevGradle is verified in T02 |
| Brain Node.js | Node 24 LTS | PINNED | Recommended runtime under OpenAI Node SDK policy; also satisfies TypeSafe SDK Node 20+ requirement |

Even though Gradle 9 exists, T00 does not adopt it. Use the latest 8.14.x patch to stabilize the common baseline across the three Minecraft build plugins first. Changes are allowed only from actual T02 build evidence.

### 2.2 Paper

| Item | T00 Pin | Status |
|---|---:|---|
| Server target | Paper 1.21.8 | PINNED / RUNTIME UNVERIFIED |
| compile API | io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT | CONFIRMED |
| Java | 21 | PINNED |
| Candidate chat entrypoint | AsyncChatEvent | CONFIRMED |
| OP check | Player/ServerOperator.isOp() | CONFIRMED |
| Folia | unsupported | fixed requirement |

Paper 1.21.8 Javadocs provide the 1.21.8-R0.1-SNAPSHOT API. Paper chat documentation states that AsyncChatEvent may be asynchronous and that directly using Bukkit APIs in that handler is unsafe. Therefore, **separate chat-text admission/broadcast behavior from server/player/world access**, and perform Bukkit-object access on a safe server thread through the Paper scheduler.

OP authority comes from the server-provided isOp() result, not from the LLM or a separate permission node. Re-check before admission, immediately before Tool execution, and immediately before result delivery.

Official references:
- https://jd.papermc.io/paper/1.21.8/
- https://docs.papermc.io/paper/dev/chat-events/
- https://jd.papermc.io/paper/1.21.8/org/bukkit/permissions/ServerOperator.html
- https://docs.papermc.io/paper/dev/project-setup/
- https://github.com/PaperMC/Paper/blob/main/LICENSE.md

### 2.3 Fabric

| Item | T00 Pin | Status |
|---|---:|---|
| Minecraft | 1.21.8 | PINNED |
| Fabric Loader | 0.17.2 | PINNED / RUNTIME UNVERIFIED |
| Fabric API | 0.133.4+1.21.8 | CONFIRMED / PINNED |
| Yarn | 1.21.8+build.1 | CONFIRMED / PINNED |
| Fabric Loom | 1.12.2 | CONFIRMED / PINNED |
| Java | 21 | PINNED |
| Candidate chat entrypoint | ServerMessageEvents.ALLOW_CHAT_MESSAGE | CONFIRMED |
| OP check | PlayerManager.isOperator(GameProfile) | CONFIRMED |

Fabric API 0.133.4+1.21.8 `ServerMessageEvents.ALLOW_CHAT_MESSAGE` can suppress server broadcast of player chat via its return value. Direct JARVIS invocations also remain visible in public chat, so approved invocations allow broadcast.

Caution: the official Javadoc does not explicitly document the callback threading contract. T07 verifies whether the callback actually runs on the dedicated-server thread, and **must not assume thread safety that is not documented**. Convert Minecraft world/player objects to snapshots from the server execution queue.

Use Yarn 1.21.8 `PlayerManager.isOperator(GameProfile)` for the OP check. Do not grant JARVIS access based only on permission level.

Official references:
- https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.133.4%2B1.21.8/
- https://maven.fabricmc.net/docs/fabric-api-0.133.4%2B1.21.8/net/fabricmc/fabric/api/message/v1/ServerMessageEvents.html
- https://maven.fabricmc.net/net/fabricmc/yarn/1.21.8%2Bbuild.1/
- https://maven.fabricmc.net/docs/yarn-1.21.8%2Bbuild.1/net/minecraft/server/PlayerManager.html
- https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.17.2/
- https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.12.2/
- https://github.com/FabricMC/fabric-loader
- https://github.com/FabricMC/fabric-api
- https://github.com/FabricMC/fabric-loom

### 2.4 NeoForge

| Item | T00 Pin | Status |
|---|---:|---|
| Minecraft | 1.21.8 | PINNED |
| NeoForge | 21.8.52 | CONFIRMED / PINNED |
| ModDevGradle | 2.0.147 | CONFIRMED / PINNED |
| Java | 21 | CONFIRMED / PINNED |
| Candidate chat entrypoint | ServerChatEvent | CONFIRMED; exact 21.8.52 signature verified by T02/T08 compilation |
| OP check | MinecraftServer PlayerList operator list | PINNED; exact mapped signature verified by T02 compilation |

Official NeoForge 1.21.6–1.21.8 documentation requires 64-bit Java 21. The 21.8.52 artifact exists in the official NeoForged Maven repository. ModDevGradle 2.0.147 was verified in the Gradle Plugin Portal.

NeoForge `ServerChatEvent` runs on the logical server and remains cancellable. The T00 survey did not fully pin the detailed 1.21.8 API signature from official documentation, so T02/T08 **re-validate against the 21.8.52 source/compile result as authority**.

Official references:
- https://docs.neoforged.net/docs/1.21.8/gettingstarted/
- https://maven.neoforged.net/releases/net/neoforged/neoforge/21.8.52/
- https://plugins.gradle.org/plugin/net.neoforged.moddev/2.0.147
- https://github.com/neoforged/NeoForge
- https://github.com/neoforged/ModDevGradle

## 3. Brain AI SDK / Model Pins

| Item | T00 Pin | Status | Notes |
|---|---:|---|---|
| OpenAI JS SDK | openai 7.22.0 | CONFIRMED / PINNED | 2026-09-22 immutable release that added GPT-6 Sol/Luna identifiers |
| OpenAI model | gpt-6-luna | CONFIRMED / LIVE UNVERIFIED | Uses Responses API + function calling |
| reasoning effort | medium | PINNED | Luna official default, explicitly pinned for reproducibility |
| TypeSafe JS SDK | @typesafe-ai/sdk 0.6.0 | CONFIRMED / PINNED |
| TypeSafe model | jev-1.13.0 | CONFIRMED / LIVE UNVERIFIED | versioned ID pinned instead of an alias |
| Brain Node | 24 LTS | PINNED | OpenAI recommendation + satisfies TypeSafe Node 20+ |

The official OpenAI GPT-6 Luna model page documents Responses API function calling and `reasoning.effort` values `none/low/medium/high/xhigh/max`. T00 pins `medium`.

OpenAI SDK 7.22.0 is the first verified release that explicitly added the GPT-6 Luna identifier, so this version is pinned. Automatic minor/major upgrades are prohibited.

TypeSafe Models documentation identifies `jev-1.13.0` as the current stable Jev and notes that `jev-latest` points to it. Because aliases may move, use the versioned ID so product evaluation metrics do not silently change. Korean/CJK performance is not guaranteed to match English, so do not use the confidence threshold as an authority policy before A09 evaluation.

TypeSafe SDK 0.6.0 is an early SDK release published on 2026-09-15 and has recent issues. Do not stack SDK retries with JARVIS retries; preserve the 3-second deadline, read-only fallback, and mutation-Tool suppression policy.

Official references:
- https://developers.openai.com/api/docs/models/gpt-6-luna
- https://developers.openai.com/api/docs/guides/function-calling
- https://github.com/openai/openai-node/releases/tag/v7.22.0
- https://github.com/openai/openai-node/blob/main/NODE_VERSION_POLICY.md
- https://docs.typesafe.ai/models
- https://docs.typesafe.ai/sdk/javascript
- https://github.com/typesafe-ai/typesafe-sdk-js/releases/tag/v0.6.0
- https://www.npmjs.com/package/%40typesafe-ai/sdk

**Live status:** No paid live call using OPENAI_API_KEY/TYPESAFE_API_KEY was made in T00. A10 evidence is captured separately in T05/T10.

## 4. Paper Provider Reuse Survey

### 4.1 CoreProtect

| Item | Decision |
|---|---|
| T00 compile target | net.coreprotect:coreprotect:24.0, provided/compileOnly |
| API | v12 |
| API compatibility range | CoreProtect 24.0+ |
| Example current user server | CoreProtect 24.1 |
| License | Artistic-2.0 |
| v0.1 | unused |
| v0.1.1 | query-only |
| v0.2 | candidate approval-based rollback; separate contract required |

CoreProtect API v12 documentation requires plugin 24.0+ and provides examples checking `APIVersion() >= 12` and `isEnabled()`. JARVIS does not enable the capability from plugin presence alone; it verifies **plugin type + API enabled + APIVersion >= 12**.

`performRollback()` requires asynchronous invocation according to API v12 documentation. v0.1.1 includes read-only lookup only, but follows the shared rule of never waiting for DB queries on the tick thread. Capture required values from Bukkit Block/Location/Player into bounded DTOs on a safe server thread, then hand Provider DB work to a separate executor.

Official references:
- https://docs.coreprotect.net/api/
- https://docs.coreprotect.net/api/version/v12/
- https://github.com/PlayPro/CoreProtect

### 4.2 WorldGuard

| Item | Decision |
|---|---|
| T00 example user server | WorldGuard 7.0.18 |
| Verified compile target | WorldGuard 7.0.14 + WorldEdit 7.3.16, Java 21 / Minecraft 1.21.8 |
| API major | 7.x |
| Required companion | WorldEdit |
| License | LGPL-3.0-or-later |
| v0.1 | unused |
| v0.1.1 | region/flags/build-protection queries |
| Mutation operations | unsupported |

EngineHub documents strong API stability within 7.x. This checkout compileOnly-verifies WorldGuard 7.0.14 and WorldEdit 7.3.16 against Java 21/Minecraft 1.21.8. The example user-server version WorldGuard 7.0.18 does not match that build target yet, so its runtime compatibility remains unverified until the Paper smoke test.

Use `RegionQuery.testState` rather than reimplementing owner/priority/flag logic for protection decisions. WorldGuard documentation warns that **RegionQuery does not automatically check bypass permission**, so questions where requester bypass matters must separately consult `SessionManager.hasBypass`.

Even if WorldGuard API data structures are thread-safe, do not treat Bukkit Player/World adapters and server-object access as equivalent. The initial v0.1.1 implementation performs this adaptation/query briefly on the server thread and immediately converts to DTOs. Split it only if actual cost becomes a problem and official threading contracts plus load measurements justify it.

Official references:
- https://worldguard.enginehub.org/en/latest/developer/dependency/
- https://worldguard.enginehub.org/en/latest/developer/regions/protection-query/
- https://github.com/EngineHub/WorldGuard

### 4.3 CMI

| Item | Decision |
|---|---|
| Example user server | CMI 9.8.9.6 + CMILib 1.5.9.9 |
| Public CMI-API documentation version | 9.8.6.4 |
| Dependency method | JitPack + provided (official API guide) |
| License | CMI-API license requires specific permission from Zrips for code use by other plugins; user confirmed such permission |
| Dependency | `com.github.Zrips:CMI-API:9.8.6.4` compileOnly; not bundled in the JARVIS distribution JAR |
| v0.1/v0.1.1 | no baseline capability; optional Tool enabled only when CMI is present |
| T14 | online nickname/AFK Tool implemented; runtime smoke pending |

The official CMI API page documents CMI-API 9.8.6.4 as a provided dependency. The user's CMI runtime is 9.8.9.6, however, and **official documentation alone does not prove compatibility between that runtime and the public API artifact**.

The official CMI-API 9.8.6.4 [`resources/LICENSE`](https://github.com/Zrips/CMI-API/blob/9.8.6.4/resources/LICENSE) requires specific permission from Zrips for code use outside plugins maintained by Zrips. The user confirmed explicit permission from Zrips. No copy of that permission is stored in this checkout. The implementation references only the official API as compileOnly and does not include the API binary in JARVIS.

CMI documentation warns that bulk offline-player information loads can burden the server. T14 exposes only currently online player lookup and excludes offline lookup, play time, and warnings. Because no general API thread-safety contract is documented, Bukkit/CMI object access is limited to the server thread. Nickname color/control codes are removed and output is capped at 64 UTF-16 units.

T14 status, the user-permission confirmation boundary, and runtime-smoke procedure are recorded in [`T14_HANDOFF.md`](../minecraft/paper/src/main/java/io/github/kardane/jarvisminecraft/paper/integrations/cmi/T14_HANDOFF.md). The capability is advertised only when CMI and CMILib are active and the API probe succeeds. Binary compatibility on the target server must be confirmed by smoke testing.

Official references:
- https://www.zrips.net/cmi/api/
- https://github.com/Zrips/CMI-API/releases/tag/9.8.6.4
- https://github.com/Zrips/CMI-API/blob/9.8.6.4/resources/LICENSE

## 5. License and Redistribution Policy

| Component | Upstream License | JARVIS Treatment |
|---|---|---|
| Paper | GPL-3.0 family, some contributor code MIT | compileOnly; Paper itself not bundled |
| Fabric Loader | Apache-2.0 | loader not bundled |
| Fabric API | Apache-2.0 | declared as platform mod dependency |
| Fabric Loom | MIT | build-only |
| NeoForge | LGPL-2.1 | platform dependency; NeoForge itself not bundled |
| ModDevGradle | LGPL-2.1 | build-only |
| OpenAI JS SDK | Apache-2.0 | Brain npm dependency |
| TypeSafe JS SDK | MIT | Brain npm dependency |
| CoreProtect | Artistic-2.0 | provided/compileOnly; plugin not bundled |
| WorldGuard | LGPL-3.0-or-later | compileOnly; plugin not bundled |
| CMI / CMI-API | CMI-API license requires specific permission from Zrips for code use by other plugins; user confirmed permission | CMI-API is compileOnly; CMI/CMILib not bundled; runtime smoke pending |

This table is a build/distribution boundary decision, not legal advice. By default, JARVIS release artifacts do not shade third-party server/plugin/mod binaries.

## 6. Threading and Authority Boundaries

| Target | T00 Policy |
|---|---|
| AI HTTP/WebSocket/disk/DB | never wait on the Minecraft tick thread |
| Paper ChatEvent | check OP/session on the server thread and keep admitted input chat public; process AI requests and network work asynchronously |
| Fabric chat callback | official threading semantics are insufficiently documented; access server objects only inside server execute/scheduler boundaries |
| NeoForge ServerChatEvent | logical-server event; access/mutate world/player state only in the server execution context |
| CoreProtect DB lookup | server-thread snapshot followed by asynchronous Provider work |
| CoreProtect rollback | unsupported before v0.2; requires async execution plus approval/journal contract per API requirements |
| WorldGuard query | initially short server-thread query → DTO; check bypass separately |
| CMI | general thread safety not documented; start server-thread-only |
| OP verification | never trust model values; use the platform's actual operator registry/API as the authoritative source |

Do not pass platform/NMS/Bukkit/WorldGuard/CMI objects into the Brain or shared Java module. Retain only JARVIS DTOs across asynchronous boundaries.

## 7. Values Handed to T01/T02

T01 may assume the following contract values:

- First protocol/runtime target: Minecraft 1.21.8, Java 21.
- Brain: Node 24, OpenAI 7.22.0, gpt-6-luna + reasoning medium, @typesafe-ai/sdk 0.6.0, jev-1.13.0.
- OP authority: Paper isOp(), Fabric PlayerManager.isOperator(GameProfile), NeoForge vanilla operator list.
- An `isOp` value sent by an external model/Brain is not authority evidence.
- If a Provider is absent or its version/API check fails, do not expose the capability/Tool.
- Expose CMI capability only when the active Provider and runtime API probe succeed. Record actual server-version compatibility as separate smoke evidence.

T02 attempts the build skeleton with these fixed values:

- Gradle Wrapper 8.14.5
- Paper API 1.21.8-R0.1-SNAPSHOT
- Fabric Loader 0.17.2
- Fabric API 0.133.4+1.21.8
- Yarn 1.21.8+build.1
- Fabric Loom 1.12.2
- NeoForge 21.8.52
- ModDevGradle 2.0.147

If actual dependency resolution/compilation fails in T02, make **only the smallest version adjustment** and update this document and the ADR together.

## 8. Unverified Items / Follow-up Gates

1. Actual startup and equivalent chat UX on Paper/Fabric/NeoForge 1.21.8 dedicated servers.
2. Whether cancelling Fabric `ALLOW_CHAT_MESSAGE` leaves any signed-chat residue in the 1.21.8 client.
3. Exact compile signatures for NeoForge 21.8.52 `ServerChatEvent` and operator checks.
4. Whether de-op/logout races are blocked by the immediate pre-execution re-check on all three platforms.
5. Joint Gradle 8.14.5 + Loom 1.12.2 + ModDevGradle 2.0.147 multiproject build.
6. API v12 lookup paging/timeout and real DB-executor behavior on CoreProtect 24.1 runtime.
7. `RegionQuery` and bypass behavior on the example WorldGuard 7.0.18 server with its actual WorldEdit/FAWE combination; current compile target is WorldGuard 7.0.14 / WorldEdit 7.3.16.
8. Real binary/runtime smoke for CMI 9.8.9.6 + CMILib 1.5.9.9 + compileOnly CMI-API 9.8.6.4. The user's confirmation of Zrips permission is recorded in the T14 handoff.
9. `gpt-6-luna` Responses Tool call/result using a real OpenAI account.
10. `jev-1.13.0` model ID/classification response/request ID using a real TypeSafe account.
11. Korean Jev A09 evaluation with at least 200 cases. Do not use the confidence threshold as policy authority before that evaluation.

## 9. T00 Handoff

- Prior commit: 878ce755438ad5313b58bbbb6ef088ef6bc38b0b
- Working branch: codex/t00-compatibility
- Changed scope: docs/compatibility.md, docs/decisions/*
- Implementation code changes: none
- Paid API calls: none
- Minecraft server startup: none
- Conclusion: **T00 is complete as a documentation-contract baseline. T01 contract/schema work may begin.**
