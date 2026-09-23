# Minecraft JARVIS 통신 계약 1.0

작성일: 2026-09-24  
관련 작업: T01  
선행 기준: docs/compatibility.md, docs/Minecraft_JARVIS_WORK_SPEC.md  
정규 스키마: ../protocol/schema/protocol.schema.json

## 1. 목적과 적용 범위

이 문서는 Minecraft Adapter(Paper/Fabric/NeoForge)와 TypeScript Brain 사이의 **언어 중립 통신 계약**을 고정한다. T03~T10 구현은 이 계약을 소비하며 플랫폼별 편의를 이유로 envelope, 권한 의미, 오류 의미를 독자적으로 바꾸지 않는다.

현재 protocol version은 **1.0**이다.

T01은 wire contract만 고정한다. WebSocket 라이브러리, Java/TypeScript serializer/validator 구현, build/CI 연결은 T02/T03/T04 범위다.

## 2. 전송 계층

- Brain이 loopback 주소에 WebSocket 서버를 연다.
- Adapter가 Brain에 연결한다.
- HTTP Upgrade 요청에서 공유 비밀을 **X-Jarvis-Secret** 헤더로 전달한다.
- 비밀은 URL, query string, protocol JSON, audit log에 넣지 않는다.
- Brain은 공유 비밀을 상수 시간 비교 방식으로 검증해야 한다.
- 빈 비밀, 샘플 비밀, 인증 실패는 연결을 거부한다.
- 인증 후에도 hello 검증이 끝나기 전에는 hello 외 업무 메시지를 처리하지 않는다.
- 한 WebSocket message에는 UTF-8 JSON object 하나만 담는다.
- 최대 message 크기는 **65,536 bytes**다. byte 상한은 JSON parse 전에 적용한다.
- 압축 사용 여부는 구현 단계에서 결정할 수 있으나 해제 후 payload가 상한을 우회해서는 안 된다.

## 3. 연결 상태 전이

~~~text
DISCONNECTED
    |
    | WebSocket upgrade + secret 검증
    v
TRANSPORT_AUTHENTICATED
    |
    | Adapter -> hello
    | Brain -> hello(accepted=true)
    v
HELLO_VERIFIED
    |
    | Adapter -> capabilities
    v
ACTIVE
    |
    | disconnect / protocol error / shutdown
    v
DISCONNECTED
~~~

규칙:

1. Adapter hello의 serverId가 인증된 연결에 결합된다.
2. 같은 연결의 이후 모든 메시지는 같은 serverId를 사용해야 한다.
3. capabilities는 재접속마다 다시 교환한다.
4. 재접속은 새 연결이다. 이전 연결의 session/action을 자동 부활시키지 않는다.
5. protocol major가 맞지 않으면 UNSUPPORTED로 종료한다.
6. 현재 1.0 구현은 정확히 protocolVersion="1.0"만 허용한다.

## 4. 공통 Envelope

모든 protocol message는 아래 필드를 **전부 포함**한다. 사용하지 않는 식별자는 생략하지 않고 null을 보낸다.

| 필드 | 형식 | 의미 |
|---|---|---|
| protocolVersion | "1.0" | wire contract 버전 |
| type | string | message type discriminator |
| messageId | UUID | 개별 wire message 식별자 |
| requestId | UUID 또는 null | 하나의 사용자 요청과 그 Tool loop 전체의 식별자 |
| serverId | 1~64자 | 인증된 Minecraft 서버 식별자 |
| sessionId | UUID 또는 null | OP 대화 세션 식별자 |
| requesterUuid | UUID 또는 null | 요청한 실제 Minecraft 플레이어 UUID |
| sentAt | RFC3339 date-time | 송신 시각 |
| deadlineAt | RFC3339 date-time 또는 null | 해당 요청이 더 이상 유효하지 않은 절대 시각 |
| payload | object | type별 payload |

Tool message에는 추가로 toolCallId가 존재한다. 상태 변경 Tool에는 actionId도 존재한다.

### 식별자 의미

- messageId: 각 송신마다 새 값. transport 중복 판별용.
- requestId: 한 OP 메시지부터 최종 chat.response까지 유지한다.
- sessionId: 직접 호출로 시작한 120초 대화 범위에서 유지한다.
- toolCallId: 하나의 Tool 시도 식별자. 같은 requestId 안에서도 Tool마다 다르다.
- actionId: 서버 상태를 바꾸는 1회성 작업 식별자. v0.1에서는 teleport_staff에만 사용한다.

requesterUuid는 **권한 증거가 아니다**. Adapter가 실제 접수한 요청과 binding을 검증하기 위한 식별자일 뿐이다.

## 5. 메시지 종류와 방향

| type | 방향 | request/session/requester | 설명 |
|---|---|---|---|
| hello | 양방향 | null | protocol/platform instance 확인 |
| capabilities | Adapter -> Brain | null | 현재 실제로 사용할 수 있는 capability/Tool 목록 |
| chat.message | Adapter -> Brain | 필수 | JARVIS가 접수한 비공개 OP 입력 |
| chat.response | Brain -> Adapter | 필수 | 해당 OP에게만 전달할 평문 응답 |
| tool.request | Brain -> Adapter | 필수 | allowlist Tool 실행 요청 |
| tool.result | Adapter -> Brain | 필수 | 구조화된 Tool 결과 |
| cancel | 양방향 | requestId 필수 | 진행 요청의 best-effort 취소 |
| error | 양방향 | 상황에 따라 null | connection/request 수준 오류 |
| ping | 양방향 | null | keepalive |
| pong | 양방향 | null | ping 응답 |

Tool 실행 자체의 실패는 error message가 아니라 **tool.result의 ERROR/UNSUPPORTED 상태**로 표현한다. error message는 protocol/connection/request orchestration 실패에 사용한다.

## 6. Hello와 Capability

### Adapter hello

포함 정보:

- adapterInstanceId
- platform: paper / fabric / neoforge
- minecraftVersion: 1.21.8
- adapterVersion
- platformVersion

Brain은 이 값으로 권한을 추정하지 않는다. 호환되지 않는 platform/version이면 연결을 명시적으로 거부한다.

### Brain hello

- brainInstanceId
- brainVersion
- accepted=true

accepted=false 형태는 protocol에 두지 않는다. 거부는 error 후 연결 종료로 처리한다.

### Capability

capability가 존재하려면 **실제 runtime 검증이 끝난 Provider/플랫폼 기능**이어야 한다. 단순 플러그인 파일 존재는 capability가 아니다.

초기 capability 이름:

- server.status
- player.list
- player.lookup
- player.location
- player.nearby
- world.info
- staff.self_teleport
- history.lookup
- region.lookup
- region.protection

Brain은 capabilities 메시지의 tools 배열에 없는 Tool을 모델에 제공하지 않는다. Adapter는 그래도 실행 시점에 다시 allowlist/capability를 검사한다.

## 7. OP 및 Actor 계약

v0.1의 유일한 상호작용 주체는 **현재 접속 중이며 서버가 실제 OP로 인정한 플레이어**다.

권위 있는 판정:

- Paper: server API의 isOp()
- Fabric: PlayerManager.isOperator(GameProfile)
- NeoForge: vanilla operator registry/PlayerList의 실제 판정

다음 값은 권한 근거가 아니다.

- Brain 내부 플래그
- LLM 출력
- 요청 JSON의 임의 boolean
- jarvis.* permission node만 단독으로 충족한 상태

의도적으로 protocol schema에는 isOp 필드를 두지 않는다.

Adapter는 최소 세 번 권한을 재검사한다.

1. chat.message 접수 직전
2. tool.request 실행 직전
3. chat.response 전달 직전

deop 또는 logout이 확인되면 session을 폐기하고 진행 중 변경 실행을 새로 시작하지 않는다.

## 8. 채팅 세션 계약

~~~text
NONE
  |
  | OP가 독립 호출어로 시작
  v
ACTIVE (TTL 120초)
  |  accepted follow-up
  |--------------------+
  |                    |
  +---- TTL refresh <--+
  |
  +--> "대화 끝" -> ENDED
  |
  +--> 120초 무입력 -> EXPIRED
  |
  +--> deop/logout/disconnect -> INVALIDATED
~~~

Adapter가 처리할 규칙:

- 직접 호출 alias: 자비스 / jarvis / 재비스
- 메시지 시작의 독립 호출어만 인정한다.
- 영문은 대소문자를 무시한다.
- 자비스팅 같은 부분 문자열은 호출로 취급하지 않는다.
- 활성 세션의 "대화 끝"은 Brain에 보내지 않고 종료한다.
- 활성 세션의 "!내용"은 해당 메시지만 일반 채팅으로 보내고 Brain에 보내지 않는다.
- 비OP 일반 채팅은 Brain이나 모델로 전송하지 않는다.
- JARVIS 입력과 응답은 다른 플레이어/다른 OP에게 자동 공유하지 않는다.

세션 key는 (serverId, requesterUuid, sessionId)다. 이름은 key로 사용하지 않는다.

## 9. 요청 직렬화와 예산

세션 하나에서는 사용자 요청을 직렬화한다.

초기 한도:

- session당 실행 중 1건
- session당 대기 2건
- server당 실행 중 대화 4건
- 전체 대기 16건
- request당 Tool 최대 8회
- request당 모델 왕복 최대 4회
- 전체 request deadline 30초
- Jev deadline 3초
- 일반 조회 Tool deadline 5초

상한을 넘으면 BUSY 또는 TIMEOUT으로 종료하고 무제한 queue를 만들지 않는다.

deadlineAt은 절대 시각이다. 수신자는 자체 제한과 들어온 deadline 중 더 이른 값을 사용한다. deadlineAt <= sentAt인 업무 메시지는 INVALID_ARGUMENT으로 거절한다.

## 10. Tool 실행과 중복 방지

### Read-only Tool

actionId는 반드시 null이다.

같은 연결에서 이미 본 toolCallId가 다시 오면:

- 실행 중이면 BUSY를 반환한다.
- terminal result를 보관 중이면 같은 terminal result를 재사용할 수 있다.
- 새 Tool 실행으로 간주해서 호출량을 증폭시키지 않는다.

### State-changing Tool

v0.1에서는 teleport_staff만 해당한다.

- actionId는 UUID 필수.
- Adapter가 actionId별 실행 상태를 보관한다.
- 동일 actionId를 두 번 실행하지 않는다.
- ACK/result 손실로 결과를 확인할 수 없으면 OUTCOME_UNKNOWN이다.
- OUTCOME_UNKNOWN을 자동 재실행하지 않는다.
- 재접속 후 이전 connection/session의 state-changing request는 거부한다.
- network timeout은 Minecraft 변경이 취소됐다는 증거가 아니다.

초기 action state:

~~~text
UNSEEN
  |
  v
EXECUTING
  |------> SUCCEEDED
  |------> FAILED
  +------> OUTCOME_UNKNOWN
~~~

## 11. 취소

cancel은 best-effort orchestration 신호다.

reason:

- CLIENT_DISCONNECTED
- SESSION_ENDED
- DEADLINE_EXCEEDED
- OP_REVOKED
- SHUTDOWN

이미 완료된 Tool을 되돌리는 의미가 아니다. 특히 상태 변경이 시작된 후 cancel/timeout이 왔다고 해서 실패나 rollback으로 추측하지 않는다.

## 12. Tool Result 공통 계약

모든 Tool result에는 다음 필드가 있다.

| 필드 | 의미 |
|---|---|
| status | OK / EMPTY / ERROR / UNSUPPORTED |
| data | Tool별 구조화 데이터. ERROR/UNSUPPORTED에서는 null |
| error | ERROR/UNSUPPORTED에서 구조화 오류. OK/EMPTY에서는 null |
| observedAt | 서버/Provider가 해당 사실을 관측한 시각 |
| source | Paper, Fabric, NeoForge, CoreProtect, WorldGuard 등 실제 출처 |
| truncated | 제한 때문에 결과 일부만 반환했는지 |

EMPTY는 정상적으로 조회했지만 기록/목록이 비어 있음을 뜻한다. NOT_FOUND는 특정 요구 대상 자체를 찾지 못한 오류다. 둘을 혼동하지 않는다.

## 13. 오류 코드

고정 오류 코드:

| 코드 | 의미 |
|---|---|
| UNAUTHORIZED | 인증/현재 OP/actor binding 실패 |
| INVALID_ARGUMENT | schema 이후 의미 검증 실패 포함 |
| UNSUPPORTED | protocol/capability/기능 미지원 |
| NOT_FOUND | 정확한 대상 없음 |
| AMBIGUOUS_TARGET | 단일 UUID로 확정할 수 없음 |
| BUSY | queue/동일 Tool 실행/동시성 상한 |
| TIMEOUT | deadline 내 완료 불가 |
| PROVIDER_UNAVAILABLE | 등록 Provider가 현재 장애 |
| CANCELLED | 명시 취소 |
| OUTCOME_UNKNOWN | 변경 결과를 안전하게 확정 불가 |
| INTERNAL | 외부에 세부 stack을 노출하지 않는 내부 실패 |

error.message는 플레이어에게 노출 가능한 안전한 문장이어야 한다. API key, stack trace, SQL, filesystem secret을 넣지 않는다.

## 14. 재시도 규칙

- 상태 변경 Tool은 자동 재시도하지 않는다.
- read-only Tool은 deadline 안에서만 제한적으로 재시도할 수 있다.
- 같은 장애에 SDK retry와 JARVIS retry를 중첩해 호출량을 증폭하지 않는다.
- retry는 새로운 toolCallId를 만들지 않는다. 같은 논리 Tool 시도에 대한 transport retry는 원래 toolCallId를 유지한다.
- Provider가 결과를 반환했는지 불명확한 상태 변경은 OUTCOME_UNKNOWN으로 끝낸다.

## 15. Plain Text 출력

chat.response.payload.text는 **항상 평문**이다.

Adapter는 모델 문자열을 다음으로 해석하지 않는다.

- MiniMessage markup
- 클릭 가능한 command
- console command
- JSON chat component command
- URL 기반 자동 실행

Minecraft 플랫폼별 색상/브랜딩이 필요하면 Adapter가 신뢰 가능한 고정 prefix만 추가한다.

## 16. Schema 밖에서 반드시 검증할 의미 조건

JSON Schema 통과는 실행 허가가 아니다. 다음은 runtime validation이다.

- raw UTF-8 message <= 65,536 bytes
- deadlineAt > sentAt
- 연결에 bind된 serverId와 일치
- requestId/sessionId/requesterUuid가 Adapter가 실제 등록한 요청과 일치
- 현재 requester가 online + OP
- 현재 capability/tool allowlist에 존재
- request 예산/queue 상한
- Tool별 서버 상태 조건
- teleport_staff의 명시 이동 요청 여부
- actionId/toolCallId 중복
- result 전달 전 requester의 online + OP 재검사

## 17. 플랫폼 계약 검토

T01 작성 시 T00의 세 플랫폼 경계를 기준으로 static contract review를 수행했다.

| 항목 | Paper | Fabric | NeoForge |
|---|---|---|---|
| protocol에 플랫폼 객체 포함 없음 | PASS | PASS | PASS |
| OP 판정을 Adapter authority로 유지 | PASS | PASS | PASS |
| scheduler/execution bridge 구현 가능 | PASS | PASS | PASS |
| 비공개 requester-only 응답 표현 가능 | PASS | PASS | PASS |
| Tool DTO가 platform-neutral | PASS | PASS | PASS |
| exact runtime event signature live 검증 | T06/T10 | T07/T10 | T08/T10 |

이 표는 실제 서버 E2E 증거가 아니다. 각 Adapter 구현 담당자는 T06~T08 착수 시 이 계약을 다시 검토하고 호환 파괴 요구가 있으면 T01 계약 변경 요청을 제출한다.

## 18. Fixture 규칙

Java와 TypeScript 모두 protocol/fixtures/manifest.json을 읽어 동일 fixture를 검증한다.

- valid/*: 모두 schema 통과
- invalid/*: 모두 schema 실패
- Draft 2020-12
- UUID/date-time format validation 활성화
- type coercion 금지
- unknown field 허용 금지

T01에서 schema/fixture 구조 검증을 수행했으며, build/CI 자동화는 T02에서 연결한다.

## 19. T01 인계

T03 Java common:

- envelope/DTO 타입은 schema에서 생성하거나 schema와 1:1 대응시킨다.
- 플랫폼 객체를 DTO에 추가하지 않는다.
- 인증, binding, deadline, toolCallId/actionId 중복 방지 구현.

T04 Brain:

- session/request binding, queue와 예산, active Tool allowlist를 이 계약 기준으로 구현.
- requesterUuid나 모델 출력을 권한 증거로 사용하지 않는다.

T05 AI:

- 모델에는 capabilities에 존재하는 Tool schema만 제공.
- 모델이 만든 인자는 schema + 정책 검사를 모두 통과해야 한다.

T06~T08 Adapter:

- platform event를 이 protocol DTO로 변환.
- Tool 실행과 결과 전달 양쪽에서 현재 OP와 actor binding 재검사.
