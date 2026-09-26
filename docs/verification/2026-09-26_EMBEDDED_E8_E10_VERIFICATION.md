# 2026-09-26 Embedded Brain E8–E10 verification

## 범위

Embedded Brain 전환 계획의 E8–E10 구현을 검증한다.

- E8: Embedded Brain orchestration
- E9: JVM JSONL audit sink
- E10: Embedded Brain gateway와 Paper/Fabric/NeoForge migration wiring

Remote Brain은 parity reference로 유지한다. 기본 migration mode도 아직 `remote`다.

## E8 — Embedded Brain orchestration

추가/변경:

- `EmbeddedBrain`
- `CommonRuntime.ToolInvocation`
- `CommonRuntime.ExecutionRuntime`
- `DeadlinePolicy.validate(Instant, Instant)`
- `ChatSessionManager.end(requesterUuid, sessionId)`

Embedded 요청 흐름:

```text
ChatSessionManager
  -> EmbeddedBrain
  -> AiRequestScheduler
  -> RequestBudget
  -> Jev
  -> DeterministicRoutePolicy
  -> Luna
  -> Tool allowlist check
  -> pre-execution audit when state-changing
  -> CommonRuntime.ExecutionRuntime
  -> ServerScheduler
  -> ToolRegistry / Platform Tool
  -> Tool result history
  -> Luna final response
```

### Transport-neutral Tool execution

Embedded 경로는 내부 Tool 실행을 위해 `ProtocolMessage`나 synthetic WebSocket envelope를 생성하지 않는다.

`CommonRuntime.ToolInvocation`에는 다음 실행 binding만 들어간다.

- sentAt / deadlineAt
- requesterUuid
- requestId
- sessionId
- toolCallId
- actionId
- ToolName
- typed ToolArguments

기존 `ConnectionRuntime`은 Remote compatibility wrapper로 남아 있으며 protocol `tool.request`를 `ToolInvocation`으로 변환한다.

따라서 authority/deadline/dedupe/scheduler semantics는 Remote와 Embedded가 같은 CommonRuntime core를 사용한다.

### request/session safety

`ChatSessionManager`가 session lifecycle의 단일 authority다.

Embedded Brain은:

- submit 전 active session 확인
- scheduler 진입 후 active session 재확인
- Luna completion 후 재확인
- Tool 실행 직전 재확인
- stop 이후 running guard 재확인

을 수행한다.

`cancelSession`과 `cancelActor`는 gateway에서 `ChatSessionManager`를 즉시 갱신한 뒤 Brain queue/history/model state를 정리한다.

### Jev / Luna deadline

Jev orchestration에도 client 구현과 별개로 최대 3초 deadline guard를 둔다.

Luna는 request budget deadline을 넘으면 `TIMEOUT`으로 종료된다.

state-changing Tool deadline이 지난 뒤 결과를 확정할 수 없으면 기존 CommonRuntime의 `OUTCOME_UNKNOWN` semantics를 그대로 사용한다.

### Tool batch execution

Luna가 여러 Tool call을 반환해도 현재 Embedded 구현은 순서대로 실행한다.

이는 기존 TypeScript Brain의 deterministic sequential Tool execution과 동일한 방향이며 별도 parallel execution layer를 추가하지 않는다.

---

## E9 — JVM JSONL audit

추가:

- `AsyncJsonlAuditSink`
- `AuditMasker`
- `AuditArgumentSummaries`

기존 `AuditSink` interface를 그대로 구현하며 별도 Embedded audit interface를 만들지 않았다.

기본값:

```text
retention       = 7 days
max total       = 100 MiB
max file        = 8 MiB
max queue       = 512
```

파일 형식:

```text
jarvis-audit-YYYY-MM-DD-NNNN.jsonl
```

### fail-closed

state-changing Tool:

```text
audit.record(PRE_EXECUTION)
  -> 실제 파일 append 완료
  -> true
  -> CommonRuntime.execute
```

queue full / executor rejection / filesystem failure / storage limit failure 시 `record()`은 false로 완료되고 Tool 실행은 시작하지 않는다.

`record()`은 단순 queue enqueue 시점에 true를 반환하지 않는다.

### post-execution

post audit 실패는:

- Tool 결과 변경 금지
- Tool retry 금지
- state-changing Tool 재실행 금지

로 처리한다.

### masking

다음 key/value pattern을 sink boundary에서 다시 masking한다.

- secret
- password
- authorization
- API-key fields
- token fields
- `sk-...`
- bearer token string

---

## E10 — EmbeddedBrainGateway / platform wiring

추가:

- `EmbeddedBrainGateway`
- `EmbeddedBrainSettings`
- Paper/Fabric/NeoForge `remote | embedded` migration selection

기본값:

```text
brain mode = remote
```

Embedded mode에서는:

```text
OPENAI_API_KEY
TYPESAFE_API_KEY
```

를 Minecraft process가 직접 사용한다.

### response authority recheck

AI completion callback에서 바로 Minecraft 메시지를 보내지 않는다.

```text
AI completion
  -> ServerScheduler
  -> current online OP recheck
  -> ChatSessionManager active recheck
  -> private response
```

따라서 model 처리 중 deop/logout/session-end가 발생하면 stale response를 전달하지 않는다.

### shutdown race

gateway stop:

- Embedded Brain을 stopped 상태로 전환
- queued AI request cancellation
- request-local Luna state clear
- owned Luna client close
- owned AI executor shutdown
- audit close 시작

stop 이후 늦은 Luna completion은 running guard에서 `CANCELLED` 처리되고 Tool 실행으로 이어지지 않는다.

### AI executor

Embedded live factory는 bounded AI executor를 소유하며 Jev `HttpClient`도 같은 executor를 명시적으로 사용한다.

Minecraft server/tick thread에서는 AI HTTP completion을 기다리지 않는다.

---

## deterministic verification

새 task:

```text
:minecraft:common:embeddedBrainVerification
```

`check` task에 연결했다.

검증 항목:

### CommonRuntime

- transport-neutral direct ToolInvocation 실행
- ToolResult 보존
- Tool handler 정확히 1회 실행

### Embedded Brain loop

- User -> Luna Tool -> CommonRuntime -> Tool history -> Luna final
- 두 번째 Luna round에서 Tool result history 확인
- read-only Tool post-audit 기록

### audit fail-closed

- state-changing Tool pre-audit false
- Tool handler 0회
- PRE_EXECUTION event 시도 확인

### gateway authority race

- request 수락 후 OP revoke
- Luna final completion
- stale private response 0건

### cancellation authority

- gateway `cancelSession`
- `ChatSessionManager.isActive == false`

### shutdown race

- request 수락
- gateway stop
- 늦은 Luna final completion
- response delivery 0건

### JSONL audit

- 실제 append 성공 후 true
- secret masking
- health success timestamp
- invalid audit path에서 false
- IO failure health = UNHEALTHY

### settings

- embedded selector parsing
- migration default = remote
- blank provider key 거부

---

## CI 증거

hardening 포함 commit:

```text
9257d729c9ff8ada0a5d113e29a11468a70adf0c
```

GitHub Actions:

```text
run #168
id 36249750728
```

결과:

```text
Brain / Node   SUCCESS
Java / Gradle  SUCCESS
```

Java log:

```text
> Task :minecraft:common:embeddedBrainVerification
Embedded Brain E8-E10 verification OK
T03 verification OK
BUILD SUCCESSFUL
```

따라서 E8–E10 deterministic JVM 검증은 실제 CI에서 수행되었다.

---

## 정적 source review

- PASS: Embedded 경로가 Tool 실행용 `ProtocolMessage`를 만들지 않음
- PASS: state-changing pre-audit가 CommonRuntime Tool 실행보다 먼저 수행됨
- PASS: audit success는 append 완료 뒤에만 true
- PASS: post-audit failure가 Tool retry를 유발하지 않음
- PASS: response delivery 전 current OP/session 재검사
- PASS: cancel actor/session이 ChatSessionManager authority를 갱신
- PASS: stop 이후 late model completion 차단
- PASS: Jev orchestration deadline guard 존재
- PASS: Jev HttpClient가 owned AI executor 사용
- PASS: Paper embedded branch wiring
- PASS: Fabric embedded branch wiring
- PASS: NeoForge embedded branch wiring

---

## 아직 검증하지 않은 것

이번 단계에서 다음은 완료로 간주하지 않는다.

- 실제 TypeSafe live call
- 실제 OpenAI live function-call round trip
- Paper/Fabric/NeoForge artifact 안의 OpenAI SDK runtime bundling
- 실제 dedicated server에서 Embedded mode boot
- CoreProtect / WorldGuard / CMI 포함 Embedded E2E
- Remote/Embedded full policy parity
- WebSocket 제거
- Node production Brain 제거
- 최종 2-key-only configuration

특히 OpenAI Java SDK는 아직 common의 compile-time dependency다. 세 플랫폼 artifact에 runtime dependency가 올바르게 포함되는지는 packaging 단계에서 별도로 검증해야 한다.
