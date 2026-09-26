# JarvisMinecraft Embedded Brain 단순화 구현 계획

## 1. 목적

JarvisMinecraft를 **별도 Node.js Brain 프로세스 없이**, 서버 관리자가 자신의 플랫폼에 맞는 JAR/MOD 하나와 API 키만 설정하면 사용할 수 있는 구조로 전환한다.

최종 사용자 경험:

```text
1. Paper / Fabric / NeoForge용 JAR/MOD 설치
2. OPENAI_API_KEY 설정
3. TYPESAFE_API_KEY 설정
4. 서버 시작
5. OP가 "자비스 ..."로 사용
```

최종적으로 제거되는 운영 요소:

```text
Node.js
npm
별도 Brain daemon
localhost WebSocket
shared secret
reconnect loop
hello/capabilities handshake
Brain process monitoring
```

이 문서는 기존 `JarvisMinecraft Embedded Brain 전환 계획`을 기반으로 하되,
다음 원칙으로 단순화한 구현 계획이다.

- TypeScript Brain의 **구조를 그대로 Java로 포팅하지 않는다.**
- 기존 Brain의 **정책과 안전 불변조건(invariant)** 만 JVM으로 이식한다.
- sidecar/process boundary 때문에 존재하던 상태와 검증은 제거한다.
- 이미 `minecraft/common`에 존재하는 기능은 새로 만들지 않는다.
- Java 코드에서 Tool metadata, Tool validation, authority, deduplication의 source of truth를 하나로 유지한다.

---

# 2. 핵심 설계 원칙

## 2.1 유지해야 하는 안전 원칙

다음은 Embedded 전환 후에도 유지한다.

- 현재 접속 중인 OP만 JARVIS 사용 가능
- Jev 출력은 권한 근거가 아님
- Luna Tool call은 신뢰되지 않은 제안
- 모델이 선택한 Tool은 반드시 allowlist / capability 검증을 통과
- Minecraft 상태 변경 직전에 현재 OP 여부 재검사
- Adapter/CommonRuntime이 Minecraft 권한의 최종 경계
- 임의 console / SQL / 코드 / 파일 접근 금지
- 임의 좌표 teleport 및 타 플레이어 강제 이동 금지
- state-changing Tool 결과가 불명확하면 `OUTCOME_UNKNOWN`
- `OUTCOME_UNKNOWN` 상태 변경 Tool은 자동 retry 금지
- AI HTTP 호출로 Minecraft tick/server thread block 금지
- state-changing Tool은 pre-execution audit 실패 시 실행 거부
- deop / logout / session end race 차단
- request/session binding 유지
- Tool/model request budget 유지
- optional provider가 없으면 해당 Tool은 모델에 노출하지 않음

---

## 2.2 제거해야 하는 sidecar-era 개념

다음은 별도 프로세스 간 통신을 위해 필요했던 개념이므로,
Embedded 구조에서 새 객체/상태로 재현하지 않는다.

```text
Brain-side connection registry
serverId → connection map
authenticated adapter connection state
Brain hello / adapter hello
capabilities handshake state machine
WebSocket reconnect generation
ping / pong
shared secret authentication
ToolRequestEnvelope → WebSocket → ToolResultEnvelope 왕복
Brain-side result connection binding 검증
Brain-side AdapterPort
```

보안 정책 자체는 유지하되,
같은 JVM 내부 객체 호출에서 의미가 사라진 transport-level 검증은 제거한다.

---

# 3. 최종 목표 구조

```text
Paper / Fabric / NeoForge Server
└─ JarvisMinecraft JAR/MOD
   ├─ ChatSessionManager
   │  └─ session lifecycle / TTL / active 여부
   │
   ├─ EmbeddedBrain
   │  ├─ ConversationHistoryStore
   │  ├─ AiRequestScheduler
   │  ├─ RequestBudget
   │  ├─ JevClient
   │  │  └─ HTTPS → TypeSafe Jev
   │  ├─ DeterministicRoutePolicy
   │  ├─ LunaClient
   │  │  └─ HTTPS → OpenAI Responses API
   │  └─ AuditSink
   │
   ├─ ToolArgumentCodec
   │  └─ Luna JSON → ToolArguments
   │
   ├─ CommonRuntime
   │  ├─ ToolRegistry
   │  ├─ RequesterAuthority
   │  ├─ DeadlinePolicy
   │  ├─ DeduplicationLedger
   │  └─ ServerScheduler
   │
   └─ PlatformAdapter
      ├─ current online + OP 재검사
      ├─ server-thread 진입
      └─ 실제 Minecraft API 실행
```

모델 파이프라인:

```text
User
 ↓
Jev
 ↓
DeterministicRoutePolicy
 ↓
Luna
 ↓
Tool proposal
 ↓
ToolArgumentCodec
 ↓
Tool allowlist / capability filter
 ↓
pre-execution audit
 ↓
CommonRuntime
 ↓
Platform authority recheck
 ↓
Minecraft API
 ↓
Tool result
 ↓
Luna final response
```

Jev와 Luna는 모두 유지한다.

---

# 4. Source of Truth 정리

Embedded 전환 시 가장 중요한 목표는 **동일 정책을 여러 곳에서 소유하지 않는 것**이다.

## 4.1 Tool metadata

새 `BrainToolCatalog`을 만들지 않는다.

기존 Java `Protocol.ToolName`을 정적 Tool metadata의 source of truth로 사용한다.

```text
Protocol.ToolName
 ├─ wireName
 ├─ capability
 ├─ stateChanging
 └─ risk
```

현재 서버에서 실제 사용 가능한 Tool은 `ToolRegistry`가 소유한다.

```text
ToolName      = 정적 Tool 정의
ToolRegistry  = 실제 등록된 Tool
RoutePolicy   = 현재 요청에서 Luna에 노출할 subset
```

---

## 4.2 Session lifecycle

session lifecycle의 source of truth는 `ChatSessionManager` 하나로 유지한다.

`EmbeddedBrain` 내부에 별도의 session TTL/lifecycle store를 만들지 않는다.

대신 AI 대화 이력만 관리하는 저장소를 둔다.

```text
ChatSessionManager
 ├─ sessionId
 ├─ TTL
 ├─ direct/follow-up
 ├─ invalidate
 └─ active 여부

ConversationHistoryStore
 └─ sessionId → conversation entries
```

`ConversationHistoryStore`는 다음을 소유하지 않는다.

```text
OP 여부
session TTL
session 시작/종료 권한
Minecraft authority
```

---

## 4.3 Tool argument validation

현재 Java `ProtocolCodec`에 이미 존재하는 strict argument validation을 재사용한다.

새 AI 전용 validator를 별도로 만들지 않는다.

기존 Tool argument parsing/validation을 다음과 같이 추출한다.

```text
ToolArgumentCodec
  parse(ToolName, JsonObject)
      ↓
  ToolArguments
```

사용 위치:

```text
Remote WebSocket
 JSON
  ↓
ToolArgumentCodec
  ↓
ToolArguments
```

```text
Embedded Luna
 function arguments JSON
  ↓
ToolArgumentCodec
  ↓
ToolArguments
```

검증 항목:

- unknown field 거부
- missing field 거부
- UUID validation
- string length
- integer / number range
- location validation
- Tool별 argument shape
- inactive Tool 거부

---

## 4.4 Minecraft authority

최종 권한 검사는 `CommonRuntime + PlatformAdapter`에 둔다.

`EmbeddedBrain`은 가능한 한 policy/orchestration만 담당한다.

```text
EmbeddedBrain
  = 모델 orchestration / budget / route / audit

CommonRuntime
  = Tool execution safety / deadline / dedupe / active Tool

PlatformAdapter
  = 실제 current online OP / Minecraft state authority
```

---

# 5. 구현하지 않을 것

다음 구현은 명시적으로 금지한다.

## 5.1 JAR 내부 Node runtime

```text
JAR
 └─ bundled Node
     └─ existing brain.js
```

단일 artifact처럼 보여도 실제 구조는 sidecar와 동일하므로 채택하지 않는다.

---

## 5.2 TypeScript Brain 구조의 1:1 Java 포팅

다음 개념을 그대로 Java 객체로 옮기지 않는다.

```text
ConnectionState
AdapterPort
connection registry
WebSocket request envelope loop
Brain-side server connection authority
reconnect cancellation state
```

이식 대상은 구조가 아니라 다음 정책이다.

```text
deadline
request budget
model round budget
Tool call budget
per-session serialization
Jev routing
Tool narrowing
pre-execution audit
OUTCOME_UNKNOWN
no automatic retry
session cancellation
```

---

## 5.3 별도 BrainToolCatalog

Tool metadata는 `Protocol.ToolName`을 사용한다.

---

## 5.4 별도 Brain AuditPort

기존 Java `AuditSink` 인터페이스를 사용한다.

---

## 5.5 AI 전용 Tool validator 중복 구현

기존 protocol argument validation을 `ToolArgumentCodec`으로 추출해 공유한다.

---

## 5.6 OpenAI Responses API 전체 직접 구현

OpenAI 공식 Java SDK를 기본으로 사용한다.

raw `HttpClient + Gson` 구현은 다음 조건에서만 fallback으로 검토한다.

- 실제 Paper/Fabric/NeoForge packaging/classloader 충돌 발생
- shading/relocation으로 해결 불가
- 공식 SDK 의존성이 플랫폼 배포를 실질적으로 방해

---

# 6. 개발 순서

# Phase E0 — Architecture Decision 고정

## 목표

Embedded 전환에서 **무엇을 유지하고 무엇을 제거하는지** 먼저 문서로 고정한다.

## 작업

새 ADR 작성.

고정할 내용:

```text
Node Brain 제거
JVM Embedded Brain 채택
Jev + Luna 유지
CommonRuntime / PlatformAdapter final authority 유지
WebSocket은 Embedded parity 이후 제거
AI HTTP는 비동기 호출
Minecraft server thread blocking 금지
TypeScript Brain 구조의 1:1 포팅 금지
기존 Java runtime 재사용 우선
```

## 완료 조건

architecture/ADR에 최종 호출 흐름이 명시되어 있다.

```text
Minecraft Chat
 → EmbeddedBrain
 → Jev
 → RoutePolicy
 → Luna
 → ToolArgumentCodec
 → Audit
 → CommonRuntime
 → PlatformAdapter
 → Minecraft API
```

---

# Phase E1 — BrainGateway seam 최소 도입

## 목표

현재 플랫폼 chat/controller가 `AdapterBrainConnection`을 직접 참조하지 않게 한다.

## 인터페이스

```java
public interface BrainGateway {
    CompletionStage<Boolean> submitChat(
        UUID requesterUuid,
        String requesterName,
        UUID sessionId,
        String mode,
        String text
    );

    void cancelActor(UUID requesterUuid, CancelReason reason);

    void cancelSession(
        UUID requesterUuid,
        UUID sessionId,
        CancelReason reason
    );

    void start();

    void stop();
}
```

## 초기 구현

```text
BrainGateway
 ├─ AdapterBrainConnection   // 기존 Remote 구현이 직접 implement
 └─ EmbeddedBrainGateway
```

별도 `RemoteBrainGateway` wrapper를 새로 만드는 것은 피한다.

## 목적

- 기존 Remote Brain을 reference implementation으로 유지
- Embedded 구현과 동일 fixture/policy 결과 비교
- 플랫폼 entrypoint 변경 범위 최소화

## 삭제 계획

Remote 제거 후 interface가 불필요하면 `BrainGateway` 자체도 제거 가능하다.

---

# Phase E2 — 기존 Java runtime의 중복 제거 준비

## 목표

Embedded Brain을 만들기 전에 Java 쪽의 기존 기능을 재사용 가능한 형태로 정리한다.

## E2-1 ToolArgumentCodec 추출

현재 `ProtocolCodec` 내부 Tool argument parser를 별도 클래스로 이동한다.

예:

```java
public final class ToolArgumentCodec {
    public ToolArguments parse(
        ToolName tool,
        JsonObject arguments
    );
}
```

`ProtocolCodec`도 이 클래스를 사용하도록 변경한다.

### 완료 조건

기존 protocol fixtures와 Java 테스트가 그대로 통과한다.

---

## E2-2 Tool metadata source 통합

새 catalog를 만들지 않는다.

`Protocol.ToolName`의:

```text
wireName
capability
stateChanging
risk
```

를 Embedded Brain에서도 사용한다.

### 완료 조건

TS `catalog.ts`와 동일한 의미를 Java `ToolName`만으로 표현 가능하다.

---

## E2-3 AuditSink 재사용

기존:

```java
AuditSink
```

를 Embedded Brain의 audit contract로 사용한다.

별도 `AuditPort`는 만들지 않는다.

---

# Phase E3 — ConversationHistoryStore 구현

## 목표

AI 대화 이력과 Minecraft session lifecycle을 분리한다.

## 구현

```java
public interface ConversationHistoryStore {
    List<ConversationEntry> history(UUID sessionId);

    void append(UUID sessionId, ConversationEntry entry);

    void clear(UUID sessionId);

    void clearActor(UUID requesterUuid);
}
```

실제 key가 requester/server 정보를 더 필요로 하면 immutable composite key를 사용한다.

## 금지

`ConversationHistoryStore`가 다음을 판단해서는 안 된다.

```text
session TTL
OP 여부
session active 여부
authority
```

해당 책임은 `ChatSessionManager`에 유지한다.

## 완료 조건

다음 테스트 통과:

- session별 history isolation
- max history entry 제한
- session end 시 history 제거
- actor invalidate 시 history 제거
- 다른 session history 혼입 없음

---

# Phase E4 — RequestBudget + AiRequestScheduler 이식

## 목표

TS Brain의 필요한 concurrency/budget 정책만 Java로 이식한다.

## RequestBudget

유지:

```text
request deadline
MAX_TOOL_CALLS
MAX_MODEL_ROUNDS
remaining budget
```

가능하면 기존 generated contract constants를 재사용한다.

---

## AiRequestScheduler

Embedded 환경에서는 server dimension을 제거한다.

필요 기능:

```text
maxConcurrent
session별 active request 1개
maxQueuedPerSession
maxQueuedTotal
cancelSession
cancelActor
shutdown
```

불필요:

```text
activeByServer
cancelServer
server reconnect semantics
```

기존 Minecraft `ServerScheduler`와 명확히 구분하기 위해 이름은 `AiRequestScheduler` 사용을 권장한다.

## 완료 조건

- 동일 session 요청 순서 보장
- global concurrency 제한
- queue overflow 시 `BUSY`
- cancel 후 queued request 미실행
- Minecraft server thread block 없음

---

# Phase E5 — JevClient JVM 구현

## 목표

Jev 분류를 Java 안에서 직접 수행한다.

## 구현 방향

Java 21 `HttpClient` 기반 최소 adapter.

```java
public interface JevClassifier {
    CompletionStage<JevClassification> classify(
        JevInput input,
        Instant deadline
    );
}
```

고정:

```text
model   = jev-1.13.0
timeout = 3 seconds
retry   = 0
```

Category:

```text
SERVER_QUERY
PLAYER_QUERY
WORLD_QUERY
HISTORY_QUERY
REGION_QUERY
ACTION_REQUEST
GENERAL
UNCERTAIN
```

실패 정책:

```text
JEV_ERROR
JEV_UNCERTAIN
JEV_LOW_CONFIDENCE
        ↓
read-only fallback
        ↓
state-changing Tool 미노출
```

Jev confidence는 다음의 근거가 아니다.

```text
permission
approval
authorization
Minecraft state evidence
```

## 완료 조건

기존 TS Jev routing fixture와 Java 결과 parity 확보.

---

# Phase E6 — DeterministicRoutePolicy 이식

## 목표

현재 TS routing policy를 Java로 옮긴다.

예:

```text
SERVER_QUERY
 → get_server_status
 → get_online_players

ACTION_REQUEST
 → get_player
 → get_player_location
 → teleport_staff
```

입력:

```text
JevClassification
ToolRegistry.tools()
optional provider availability
```

출력:

```text
Luna에 노출할 ToolName subset
```

## 중요

RoutePolicy는 **권한 판정기**가 아니다.

Tool narrowing만 담당한다.

## 완료 조건

TS routing test fixture와 동일 결과.

---

# Phase E7 — LunaClient JVM 구현

## 기본안

OpenAI 공식 Java SDK 사용.

필요 기능만 감싼 좁은 adapter를 만든다.

```java
public interface LunaClient {
    CompletionStage<LunaStep> next(
        LunaTurnInput input,
        Instant deadline
    );
}
```

필요 기능:

```text
Responses API
function tools
function_call
function_call_output
output_text
reasoning effort = medium
store = false
timeout
retry = 0
```

## 상태

Luna의 function call sequence를 request 단위로만 관리한다.

```text
requestId
 ├─ response input items
 ├─ pending function call IDs
 └─ consumed Tool results
```

session lifecycle 자체를 LunaClient가 소유하지 않는다.

## fallback 검토 조건

공식 SDK가 실제 Minecraft 배포환경에서 해결 불가능한 packaging 충돌을 일으킬 때만:

```text
JDK HttpClient
+ Gson
+ narrow Responses adapter
```

로 후퇴한다.

## 완료 조건

```text
Luna
 ↓
function_call
 ↓
Tool execution
 ↓
function_call_output
 ↓
Luna final response
```

루프가 JVM 안에서 동작한다.

---

# Phase E8 — EmbeddedBrain orchestration 구현

## 목표

이전 단계의 구성요소를 하나의 요청 흐름으로 연결한다.

예:

```java
EmbeddedBrain brain = new EmbeddedBrain(
    historyStore,
    aiScheduler,
    jevClassifier,
    lunaClient,
    routePolicy,
    auditSink,
    toolArgumentCodec,
    commonRuntime,
    chatSessions,
    clock
);
```

가능하면 `platformAuthority`를 Brain에 별도 주입하지 않는다.

최종 authority는 `CommonRuntime / PlatformAdapter`에 둔다.

## 요청 흐름

```text
Chat
 ├─ ChatSessionManager active 확인
 ├─ RequestBudget 생성
 ├─ AiRequestScheduler 진입
 ├─ Conversation history 조회
 ├─ Jev classify
 ├─ deterministic route
 ├─ ToolRegistry snapshot
 ├─ Luna
 │   ↓
 │  Tool proposal
 ├─ ToolArgumentCodec strict parse
 ├─ allowed routed Tool 확인
 ├─ state-changing이면 pre-execution audit
 ├─ CommonRuntime.execute()
 ├─ Platform OP/state recheck
 ├─ Tool result
 ├─ history append
 └─ Luna final response
```

## 주의

기존 WebSocket protocol message 객체를 내부 호출 API로 억지로 유지하지 않는다.

Embedded 내부용 immutable context를 사용한다.

예:

```java
public record RequestContext(
    UUID requestId,
    String serverId,
    UUID requesterUuid,
    UUID sessionId,
    Instant deadlineAt
) {}
```

---

# Phase E9 — Audit JVM 구현

## 목표

기존 Node Brain의 audit semantics를 Java에서 유지한다.

기존 `AuditSink` 구현체로 추가한다.

```text
AuditSink
 ├─ AsyncJsonlAuditSink
 └─ FakeAuditSink
```

필요 기능:

```text
bounded queue
secret masking
7일 retention
100 MiB total
8 MiB/file
rotation
health state
async write
```

state-changing Tool:

```text
Tool proposal
 ↓
pre-execution audit
 ↓
write success?
 ├─ YES → 실행
 └─ NO  → 실행 거부
```

post-execution audit 실패:

```text
Tool outcome 변경 금지
Tool retry 금지
```

## 완료 조건

- queue full 시 state-changing 실행 거부
- IO failure 시 state-changing 실행 거부
- post-audit failure로 Tool 재실행 없음
- secret masking 검증

---

# Phase E10 — EmbeddedBrainGateway 연결

## 목표

Paper / Fabric / NeoForge entrypoint에서 Embedded Brain을 사용할 수 있게 한다.

초기에는 feature flag로 Remote/Embedded를 선택할 수 있어도 된다.

예:

```text
brain.mode = embedded | remote
```

단, migration 완료 후 `remote` 설정은 삭제한다.

## 연결

```text
Platform entrypoint
 ↓
ChatSessionManager
 ↓
BrainGateway
 ↓
EmbeddedBrainGateway
 ↓
EmbeddedBrain
```

## 완료 조건

세 플랫폼 모두 WebSocket 없이:

```text
chat
 → EmbeddedBrain
 → fake Jev/Luna
 → CommonRuntime
```

경로가 동작한다.

---

# Phase E11 — Live Jev/Luna 통합

## 목표

실제 API를 사용한 live integration 검증.

검증:

### 서버 조회

```text
OP:
"자비스 서버 상태 알려줘"
```

기대:

```text
Jev → SERVER_QUERY
 ↓
RoutePolicy
 ↓
Luna → get_server_status
 ↓
ToolArgumentCodec
 ↓
CommonRuntime
 ↓
Minecraft
 ↓
Tool result
 ↓
Luna final
```

---

### self teleport

```text
OP:
"자비스 나를 Steve한테 보내줘"
```

기대:

```text
Jev → ACTION_REQUEST
 ↓
Luna → teleport_staff proposal
 ↓
strict argument parse
 ↓
route allowlist
 ↓
pre-execution audit
 ↓
CommonRuntime
 ↓
current OP 재검사
 ↓
self teleport
 ↓
Tool result
 ↓
Luna final
```

---

# Phase E12 — Remote/Embedded parity 검증

## 목표

Node Brain 제거 전에 deterministic behavior 차이를 잡는다.

## 비교 대상

```text
non-OP 차단
session isolation
session cancellation
request deadline
Tool call budget
model round budget
inactive Tool 거부
Jev failure fallback
low-confidence fallback
pre-execution audit failure
OUTCOME_UNKNOWN
deop race
provider unavailable
queue limit
Luna invalid Tool
Luna invalid arguments
```

## 비교 방식

동일 fixture를:

```text
Remote Brain
Embedded Brain
```

양쪽에 실행한다.

결과는 protocol wire format 자체보다 **정책 결과**를 비교한다.

예:

```text
ALLOW / DENY
Tool subset
ErrorCode
state-changing 실행 여부
final ToolResult
```

## 완료 조건

정책 parity 확보.

---

# Phase E13 — WebSocket 제거

Embedded parity 확보 후 삭제한다.

삭제 대상:

```text
AdapterBrainConnection의 Remote 구현 부분
JdkBrainWebSocketTransport
BrainTransport
SharedSecretAuthenticator
ReconnectScheduler
RemoteAdapter
BrainWebSocketServer
hello/capabilities handshake
ping/pong
reconnect
X-Jarvis-Secret
localhost:8181
```

단, Embedded에서 필요한 일부 기존 타입은 독립시켜 유지할 수 있다.

예:

```text
ToolName
ToolArguments
ToolResult
ErrorCode
Risk
CancelReason
GeneratedContractConstants
```

## ProtocolCodec 처리

외부 WebSocket protocol이 완전히 사라진다면
`ProtocolCodec` 전체를 유지할 이유는 없다.

단:

```text
ToolArgumentCodec
ToolModels
contract fixtures
generated constants
```

는 유지한다.

---

# Phase E14 — Node Brain 제거

Embedded 검증 완료 후 TypeScript production Brain 제거.

삭제 후보:

```text
brain/src/core
brain/src/server
brain/src/ops
brain/src/ai
brain/package.json
brain/package-lock.json
```

단, 평가/계약 자산은 유지한다.

```text
evals/
protocol/fixtures/
datasets/
```

필요하다면 TS 테스트 fixture를 Java 테스트 데이터로 이전한 뒤 삭제한다.

핵심:

```text
Node runtime 제거
≠
평가 자산 삭제
```

---

# Phase E15 — Config 단순화

## 실제 필수값

```text
OPENAI_API_KEY
TYPESAFE_API_KEY
```

## default 제공

```text
enabled = true
audit.directory = <plugin/mod data directory>/audit
serverId = stable auto-generated local server id
```

`serverId` override는 필요할 경우에만 제공한다.

모델은 당분간 고정:

```text
Jev  = jev-1.13.0
Luna = gpt-6-luna
Luna reasoning = medium
```

삭제:

```text
JARVIS_BRAIN_URL
JARVIS_SHARED_SECRET
JARVIS_BRAIN_HOST
JARVIS_BRAIN_PORT
JARVIS_RECONNECT_DELAY_TICKS
```

---

# Phase E16 — Packaging 검증

각 플랫폼 artifact가 다음을 포함해야 한다.

```text
common
EmbeddedBrain
Jev client
Luna client
Audit implementation
Tool execution runtime
```

산출물:

```text
jarvisminecraft-paper.jar
jarvisminecraft-fabric.jar
jarvisminecraft-neoforge.jar
```

검증:

- Node 설치 불필요
- npm 불필요
- localhost listener 없음
- shared secret 없음
- Brain daemon 없음
- artifact 하나만 서버에 복사
- API key 설정 후 기동 가능
- OpenAI SDK dependency packaging 정상
- Gson/classloader 충돌 없음
- Paper/Fabric/NeoForge 각각 clean server boot 성공

---

# Phase E17 — 최종 실제 E2E

환경:

```text
Java 21
Minecraft 1.21.8
Node.js 없음
npm 없음
Brain daemon 없음
```

필수 시나리오:

## 1. 일반 서버 조회

```text
"자비스 서버 상태 알려줘"
```

## 2. 플레이어 조회

```text
"자비스 Steve 지금 어디 있어?"
```

## 3. self teleport

```text
"자비스 나를 Steve한테 보내줘"
```

## 4. deop race

```text
1. OP 요청
2. Luna 처리 중 deop
3. Tool 실행 전 current authority 재검사
4. 실행 차단
5. stale 응답 전달 차단
```

## 5. logout race

Tool 실행 전 requester logout 시 실행 차단.

## 6. Provider 없음

CoreProtect / WorldGuard / CMI가 없으면 관련 Tool 미노출.

## 7. Jev 장애

read-only fallback만 노출.

## 8. Luna 장애

Minecraft 상태를 추측하지 않고 안전한 실패 응답.

## 9. audit 장애

state-changing Tool 실행 거부.

## 10. Tool timeout

read-only:

```text
TIMEOUT
```

state-changing:

```text
OUTCOME_UNKNOWN
no retry
```

## 11. AI latency

Jev/Luna HTTP 처리 중 server/tick thread block 없음.

---

# 7. 구현 우선순위 요약

실제 작업 순서는 다음을 권장한다.

```text
1. E0  ADR 고정
2. E1  BrainGateway seam
3. E2  ToolArgumentCodec / metadata / AuditSink 재사용 정리
4. E3  ConversationHistoryStore
5. E4  RequestBudget + AiRequestScheduler
6. E5  JevClient
7. E6  DeterministicRoutePolicy
8. E7  LunaClient
9. E8  EmbeddedBrain orchestration
10. E9 Audit JVM 구현
11. E10 플랫폼 Embedded 연결
12. E11 실제 Jev/Luna live 검증
13. E12 Remote/Embedded parity
14. E13 WebSocket 제거
15. E14 Node Brain 제거
16. E15 Config 단순화
17. E16 Packaging 검증
18. E17 3플랫폼 실제 E2E
```

중요한 dependency:

```text
E2
 ↓
E3 / E4
 ↓
E5 / E6 / E7
 ↓
E8
 ↓
E9 / E10
 ↓
E11
 ↓
E12
 ↓
E13
 ↓
E14
 ↓
E15 / E16
 ↓
E17
```

---

# 8. 단계별 삭제 기준

코드를 새로 만드는 것만큼
**언제 기존 코드를 삭제할지**를 명확히 한다.

## Remote Brain 삭제 조건

다음이 모두 충족되어야 한다.

- Embedded deterministic test parity
- Jev live test
- Luna function-call loop live test
- state-changing pre-audit test
- deop/logout race test
- Paper E2E
- Fabric E2E
- NeoForge E2E

그 전에는 Remote Brain을 reference implementation으로 유지한다.

---

# 9. 과엔지니어링 방지 체크리스트

새 클래스를 추가하기 전에 아래를 확인한다.

## 상태 저장소를 추가하려는 경우

```text
이미 ChatSessionManager가 소유하는 상태인가?
이미 RequestBindingRegistry가 소유하는 상태인가?
이미 CommonRuntime가 소유하는 상태인가?
```

YES이면 새 store를 만들지 않는다.

---

## Tool 관련 클래스를 추가하려는 경우

```text
ToolName에 이미 metadata가 있는가?
ToolRegistry에 이미 availability 정보가 있는가?
ToolArgumentCodec으로 처리 가능한가?
```

YES이면 새 catalog/schema validator를 만들지 않는다.

---

## 보안 검사를 추가하려는 경우

다음 두 종류를 구분한다.

### 유지 대상

```text
Minecraft authority
current OP
active session
active Tool
capability
deadline
dedupe
audit
state-changing safety
```

### 제거 대상

```text
WebSocket peer identity
shared secret
connection generation
hello handshake
ping/pong
reconnect
remote response binding
```

---

## HTTP client를 직접 만들려는 경우

먼저 공식 SDK가 제공하는지 확인한다.

OpenAI:

```text
공식 Java SDK 우선
```

Jev:

```text
공식 Java SDK가 없다면 narrow HttpClient adapter
```

---

# 10. 완료 정의

- [ ] Jev + Luna 동시 사용
- [ ] Node.js 설치 불필요
- [ ] npm 불필요
- [ ] 별도 Brain 프로세스 불필요
- [ ] localhost WebSocket 없음
- [ ] shared secret 없음
- [ ] Paper JAR 하나로 실행 가능
- [ ] Fabric MOD 하나로 실행 가능
- [ ] NeoForge MOD 하나로 실행 가능
- [ ] 필수 사용자 설정은 API key 2개
- [ ] Jev failure read-only fallback
- [ ] Luna Tool proposal strict validation
- [ ] Tool argument validation single source
- [ ] Tool metadata single source
- [ ] session lifecycle single source
- [ ] current online OP final authority 유지
- [ ] Tool capability filtering 유지
- [ ] deop/logout race 차단
- [ ] Tool/model budget 유지
- [ ] audit fail-closed 유지
- [ ] `OUTCOME_UNKNOWN` no-retry
- [ ] CoreProtect / WorldGuard / CMI optional capability 유지
- [ ] server/tick thread AI blocking 없음
- [ ] Remote/Embedded policy parity 검증
- [ ] 세 플랫폼 dedicated server E2E 통과

---

# 11. 최종 설계 판단

이번 전환의 목표는:

```text
TypeScript Brain
        ↓
Java Brain 복제
```

가 아니다.

목표는:

```text
TypeScript Brain의 안전 정책
        ↓
기존 Java runtime에 흡수
        ↓
process-boundary 코드 제거
```

이다.

따라서 최종 Embedded 구조에서 새로 필요한 핵심 코드는 대략 다음으로 제한한다.

```text
EmbeddedBrain
ConversationHistoryStore
AiRequestScheduler
RequestBudget
JevClient
DeterministicRoutePolicy
LunaClient
AsyncJsonlAuditSink
ToolArgumentCodec
```

반면 다음은 기존 Java 구현을 재사용한다.

```text
ChatSessionManager
ToolRegistry
ToolName
Tool DTO
CommonRuntime
RequesterAuthority
DeadlinePolicy
DeduplicationLedger
ServerScheduler
StandardMinecraftToolService
Provider Registry
Platform adapters
AuditSink
generated contract constants
```

이 구성이 **JAR/MOD 하나로 운영한다는 목표와 가장 잘 맞고**,
기존 sidecar 구조의 복잡성을 JVM 내부에 다시 재현하는 것을 피한다.
