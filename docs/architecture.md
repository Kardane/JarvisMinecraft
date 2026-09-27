# Minecraft JARVIS 아키텍처

갱신일: 2026-09-27  
범위: Paper, Fabric, NeoForge Adapter와 JVM 내부 Embedded Brain의 현재 구조.

## 현재 구조

JARVIS는 별도 Node/Brain daemon 없이 Minecraft 서버 JVM 안에서 동작한다. 플랫폼 Adapter는 현재 온라인 플레이어 identity를 구성하고 `InteractionCoordinator`의 audience/호출어/session 정책을 통과한 입력만 `EmbeddedBrainGateway`로 전달한다. Embedded Brain은 Jev 분류, deterministic Tool narrowing, GPT-6 Luna Tool loop, 감사, 예산과 queue를 JVM 내부에서 수행한다. Minecraft Tool 권한은 interaction audience와 분리되어 있으며 현재는 online OP에게만 노출된다.

```mermaid
flowchart LR
  P["설정상 허용된 온라인 플레이어"] --> AD["Minecraft Adapter / InteractionCoordinator"]
  AD --> EB["EmbeddedBrainGateway / EmbeddedBrain"]
  EB --> JV["TypeSafe Jev HTTPS"]
  EB --> GPT["OpenAI GPT-6 Luna"]
  EB --> CR["CommonRuntime"]
  CR --> API["Minecraft API / Optional Provider"]
  API --> CR
  CR --> EB
  EB --> AD
  AD --> P
```

WebSocket, shared-secret authentication, hello/capabilities handshake, ping/pong, reconnect generation, 별도 Brain process registry는 E13/E14에서 제거되었다.

## 구성 요소

| 구성 요소 | 책임 |
|---|---|
| `minecraft/common` | `BrainGateway`, `EmbeddedBrain`, Jev/Luna client, route policy, conversation history, request budget/scheduler, delayed/repeating action scheduler, audit, runtime-policy config snapshot/validation, Tool argument validation, `CommonRuntime`, Tool registry, authority/deadline/deduplication |
| `minecraft/paper` | Paper entrypoint, chat/session integration, scheduler/platform access, standard Tool, CoreProtect/WorldGuard/CMI optional Provider |
| `minecraft/fabric` | Fabric dedicated-server entrypoint, chat controller, scheduler/platform access, standard Tool |
| `minecraft/neoforge` | NeoForge dedicated-server entrypoint, chat controller, scheduler/platform access, tick sampler, standard Tool |
| `protocol/schema`, `protocol/fixtures` | 과거 Remote wire contract와 호환성/회귀 분석을 위해 보존하는 정적 계약 자산 |
| `evals` | Jev 평가 데이터와 E12 policy parity fixture |
| `tests/acceptance/out` | T10에서 수집된 과거 live acceptance evidence snapshot |

## 요청 흐름

1. 플랫폼 Adapter가 현재 온라인 플레이어 identity를 만들고 `InteractionCoordinator`가 `OP / WHITELIST / ALL / BLACKLIST` audience, configurable wake word, active session, 종료/escape를 판정한다.
2. `EmbeddedBrainGateway`가 현재 identity와 interaction authorization을 다시 확인하고 request를 생성해 bounded `AiRequestScheduler`로 넘긴다. requester가 현재 OP가 아니면 request의 Tool set은 빈 집합으로 고정된다.
3. `ExecutionPolicy`가 현재 runtime config, requester Tool authority, interaction origin으로 active Tool set을 먼저 제한한다.
4. Jev가 latest message, short topic, interaction origin, capability 이름을 받아 `engagement + route + reasoning`을 한 요청에서 판단한다.
5. `DeterministicRoutePolicy`가 execution-filtered Tool set을 route category에 맞게 좁힌다. Jev 오류, `UNCERTAIN`, 저신뢰 fallback에서는 read-only Tool만 노출한다.
6. `ReasoningPolicy`가 runtime config와 Jev 결과를 결합해 요청별 `NONE / LOW / MEDIUM / HIGH` 값을 확정하고, Luna의 모든 model round에 동일한 effort를 적용한다.
7. Luna는 허용된 Tool schema만 보고 Tool call을 제안한다. 후속 model round와 실제 Tool 실행 직전에도 최신 `ExecutionPolicy`를 다시 적용한다.
8. Tool 인자는 공용 `ToolArgumentCodec`으로 다시 strict parsing된다. 모델 출력은 실행 권한이 아니다.
9. 상태 변경 Tool은 pre-execution audit 성공 후 최신 `ExecutionPolicy`를 한 번 더 통과해야 `CommonRuntime.ExecutionRuntime`으로 전달된다.
10. `CommonRuntime`이 current OP, active Tool, deadline, deduplication/action semantics를 재검사하고 플랫폼 scheduler에서 실제 Minecraft/Provider API를 호출한다.
11. Tool result를 Luna가 해석해 최종 답을 만든다.
12. `EmbeddedBrainGateway`는 server thread로 돌아가 현재 interaction authorization과 active session을 다시 확인한 뒤 응답을 public chat으로 broadcast한다.

## 권한과 안전 불변조건

- interaction admission의 source of truth는 현재 online identity + `AudiencePolicy`다. Minecraft Tool authority의 source of truth는 계속 현재 online + OP 상태다.
- Jev/Luna 출력, requester UUID 문자열, capability 이름은 권한 증거가 아니다.
- Tool은 현재 `ToolRegistry`에 실제 등록된 경우에만 활성화된다.
- unknown/missing Tool argument field, 잘못된 UUID/range/selector는 거부한다.
- session별 요청 직렬화와 bounded queue를 유지한다.
- request당 Tool 최대 8회, 모델 왕복 최대 4회, 전체 deadline 30초를 유지한다.
- 상태 변경 Tool은 audit fail-closed다.
- 상태 변경 결과가 deadline 안에 확정되지 않으면 `OUTCOME_UNKNOWN`이며 자동 retry하지 않는다.
- audience 탈락/deop/logout/session end 후 stale reply를 차단한다. Tool 실행은 별도로 current OP를 재확인한다.
- optional Provider가 성공적으로 활성화되지 않으면 그 Provider Tool은 Luna에 노출하지 않는다.

## Runtime policy configuration

Phase 1 introduced `JarvisConfig`, `JarvisConfigLoader`, and
`ConfigManager` as a non-secret immutable runtime-policy snapshot. Paper
adapts Bukkit YAML values through `PaperJarvisConfigSource`; Fabric and
NeoForge read the optional
`config/jarvisminecraft/jarvis.properties` file through the common
properties source. Missing Fabric/NeoForge policy files use built-in defaults.

Phase 2 consumes the base interaction portion through
`InteractionCoordinator`, `AudiencePolicy`, and `InvocationMatcher`.
Wake words, follow-up TTL, and `OP / WHITELIST / ALL / BLACKLIST` admission
are live.

Phase 8 completes `ACTIVE` mode. Allowed public chat remains normal public
chat while `AmbientConversationTracker` keeps bounded in-memory context.
Only one proactive Jev classification may be in flight at a time, with a
one-second minimum classification interval. Jev receives
`PROACTIVE_CANDIDATE`; only `START_CONVERSATION` at or above the configured
confidence threshold may create a JARVIS session. Jev failure, invalid output,
`IGNORE`, low confidence, audience change, an already-active requester
session, or cooldown all fail closed with no unsolicited reply.

A successful proactive decision starts a requester-scoped follow-up session and
submits bounded ambient context using the `PROACTIVE` origin. The normal
public message is never cancelled.

Phase 3 now consumes the model reasoning portion. `JdkJevClassifier` asks
three typed choice questions in parallel: `engagement`, `route`, and
`reasoning`. `ReasoningPolicy` applies the following precedence:

1. concrete config mode (`NONE / LOW / MEDIUM / HIGH`) wins;
2. `AUTO` uses the validated Jev reasoning choice;
3. Jev error/invalid model/output uses the configured concrete fallback.

The chosen effort is fixed for the whole Brain request, including later Tool
rounds. `DIRECT/FOLLOW_UP/PROACTIVE` are already admitted response paths and
use Jev engagement only as classification metadata. `PROACTIVE_CANDIDATE`
uses `START_CONVERSATION/IGNORE` as the Phase 8 admission signal.

Phase 4 now consumes the response portion. AI final/error replies and delayed
progress messages use a platform-neutral `StyledChatMessage`. Only the
configured prefix interprets legacy Minecraft ampersand codes
(`&0..&f`, `&k..&o`, `&r`); model-generated body text remains plain.

If a request exceeds `waiting-message.threshold-ms`, one progress message is
queued. Completion marks the progress handle before final delivery, and the
server-thread delivery path checks it again so a late progress message cannot
appear after the final response.

When response sound is enabled, final/error chat stays public while the sound
is played only to the requester. Sound feedback failure is non-critical.

Phase 5 now consumes `jarvis.execution.*` through `ExecutionPolicy`.

- `READ_TALK`: active read-only Tools only.
- `EXECUTE_LITE`: read-only Tools plus explicitly allowlisted LOW-risk state-changing Tools.
- `EXECUTE`: read-only Tools plus explicitly allowlisted state-changing Tools.
- selected-mode `deny-tools` overrides allow and may also hide read-only Tools.
- non-OP requesters still receive no Minecraft Tools regardless of mode.
- proactive origins are hard-blocked from state-changing Tools.

Allow/deny entries use exact `ToolName.wireName()` values. Unknown names are a
configuration error; allow lists may contain only state-changing Tools, and the
LITE allow list may contain only LOW-risk Tools.

An in-flight request cannot gain newly permitted mutation Tools after it starts.
Policy tightening is re-applied before every model round and again immediately
before Tool execution, including after pre-execution audit.

Phase 6 adds two LOW-risk structured actions to the registered catalog:
`weather_set` and `time_set`. They are available only when the selected
execution mode explicitly allowlists them. Both are rechecked by
`ExecutionPolicy`, pre-execution audit, `CommonRuntime`, current online OP
authority, and the platform loaded-world lookup before mutation.

Phase 7 wires `jarvis.scheduling.*` through Brain-level scheduling controls.
`schedule_action` can defer or repeat only `teleport_staff`,
`weather_set`, and `time_set`. Delay is bounded by the configured maximum
and never exceeds 60 seconds. Repeating schedules require both interval and
duration; duration never exceeds 60 seconds.

Registration returns a schedule ID immediately instead of keeping the original
30-second Brain request open. Every scheduled run receives a new action ID and
short Tool deadline, then rechecks scheduling policy, execution policy, active
Tool registration, pre-execution audit, and current online OP authority through
`CommonRuntime`. Runs are serialized; a failed, denied, cancelled, timed-out,
or outcome-unknown run stops the remaining repetition. Pending schedules are
memory-only and are cancelled on actor invalidation or Brain shutdown.

Admin reload commands are still not wired.

Provider credentials and logical server identity remain in
`EmbeddedBrainSettings`; they are not copied into `JarvisConfig`.
`ConfigManager.reload()` replaces the snapshot only after a complete
successful parse, otherwise the previous valid snapshot remains active.

## Session과 conversation state

`ChatSessionManager`가 session ID, TTL, active 여부, 종료와 invalidation을 단독 소유한다. Embedded Brain은 별도의 session TTL store를 만들지 않고 `ConversationHistoryStore`에 모델 대화 이력만 보관한다.

기본 session TTL은 120초지만 `jarvis.interaction.follow-up-seconds`로 변경할 수 있다. 직접 호출어 기본값은 `자비스`, `jarvis`, `재비스`이며 `jarvis.interaction.wake-words`로 교체할 수 있다. 호출어는 메시지 시작의 독립 토큰으로만 인정한다. `대화 끝`과 `!내용`은 모델 호출 전에 로컬 처리한다.

`WHITELIST`와 `BLACKLIST`는 현재 접속 플레이어의 정확한 profile name을 대소문자 무시 비교한다. audience에서 허용된 비OP는 일반 Luna 대화는 가능하지만 request 단위 `toolsAllowed=false`가 적용되어 Minecraft Tool schema를 받지 않는다.

## Tool 경계

정적 Tool metadata는 `Protocol.ToolName`, 현재 활성 Tool set은 `ToolRegistry`가 소유한다. 기존 v0.1 상태 변경 Tool은 요청자 본인을 온라인 대상 플레이어 위치로 이동하는 `teleport_staff`였고, Phase 6에서 구조화된 `weather_set`과 `time_set`을 추가했다.

Phase 6 action Tool은 모두 `Risk.LOW`이며 raw command를 만들지 않는다. `weather_set`은 loaded world 한 곳의 `CLEAR / RAIN / THUNDER`를 1~3600초 범위로 설정하고, `time_set`은 loaded world 한 곳의 day count를 유지하면서 time-of-day 0~23999만 변경한다. 기본 `READ_TALK`에서는 세 state-changing Tool 모두 Luna에 노출되지 않는다.

CoreProtect, WorldGuard, CMI 기능은 Paper에서 optional Provider로 로딩하며 초기화 실패/의존성 부재 시 관련 Tool을 등록하지 않는다.

## AI와 스레드 경계

Jev와 Luna 네트워크 호출은 Minecraft server/tick thread를 점유하지 않는다. 플랫폼 API 호출만 각 플랫폼의 scheduler/execution context에서 수행한다. Jev에는 최신 사용자 메시지, bounded short topic, interaction origin, capability 이름만 전달한다. Luna에는 요청 처리에 필요한 bounded 대화, capability, 허용된 Tool schema/result만 전달한다. Progress delay는 JDK delayed executor에서 기다리고 실제 메시지/사운드 API 호출은 platform `ServerScheduler`로 되돌린다.

## 감사

Embedded Brain의 `AsyncJsonlAuditSink`는 bounded queue, JSONL rotation/retention, masking과 health 상태를 제공한다. state-changing Tool의 pre-execution record가 실제 저장되지 않으면 실행을 거부한다. post-execution audit 실패는 이미 실행된 Tool을 retry하게 만들지 않는다.

## E12-E16 전환 결과

- E12: Remote reference와 Embedded Java가 같은 shared parity fixture를 통과하도록 정책 회귀 테스트를 추가했다.
- E13: WebSocket transport, shared-secret auth, protocol codec/message DTO, request-binding connection registry, 플랫폼 Remote wrapper/config를 제거했다.
- E14: TypeScript/Node production Brain, WebSocket server/daemon/CLI, Node CI job을 제거했다.
- E15: provider API key 두 개만 필수 설정으로 남기고, logical server ID는 플랫폼 data directory에 안정적으로 자동 생성/영속화한다.
- E16: 공식 OpenAI Java SDK runtime을 플랫폼 artifact 안에 포함하고 dependency package를 내부 namespace로 relocation한다. Paper/Fabric/NeoForge 각각 단일 deployable artifact와 clean-server boot smoke를 검증한다.
- protocol schema/fixtures, Jev 평가 데이터, E12 fixture, 과거 T10 evidence는 분석 및 회귀 기준으로 보존한다.

과거 Remote 전송 계약과 T10 결과는 역사적 검증 자료다. 현재 production runtime path에는 Node process나 WebSocket 연결이 존재하지 않는다.

## 빌드와 운영

현재 production build 기준은 Java 21 + Gradle이다.

```bash
./gradlew build
```

실제 provider live smoke는 credentials가 있을 때 별도 실행한다.

```bash
OPENAI_API_KEY=... TYPESAFE_API_KEY=... \
  ./gradlew :minecraft:common:embeddedBrainLiveVerification
```

구체적인 운영 설정과 장애 절차는 [operations.md](operations.md)를 따른다. Embedded 전환 결정은 [ADR-0007](decisions/0007-embedded-brain-boundary.md)에 기록한다.


## ACTIVE proactive flow — Phase 8

```text
allowed PUBLIC_CHAT
  -> normal Minecraft public chat continues
  -> AmbientConversationTracker
  -> asynchronous Jev(PROACTIVE_CANDIDATE)
  -> START_CONVERSATION + confidence threshold
  -> server-thread recheck: ACTIVE + audience + requester session + cooldown
  -> start requester session
  -> EmbeddedBrain PROACTIVE request
  -> read-only Tool ceiling only
  -> public JARVIS reply
```

ACTIVE proactive classification does not block the Minecraft tick thread.
Paper moves observation through its server scheduler before entering the
gateway; Fabric and NeoForge observe from their server-thread chat callbacks.
Jev HTTP work remains asynchronous.

`ExecutionPolicy` rejects every state-changing Tool for
`PROACTIVE/PROACTIVE_CANDIDATE`, including scheduling control Tools. An OP
proactive turn may still use currently allowed read-only server queries. A
non-OP proactive turn receives no Minecraft Tools.

Proactive turns do not emit delayed progress/waiting messages. Final responses
use the normal public prefix and requester-only sound policy, and the created
session accepts normal follow-up messages.
