# Minecraft JARVIS 병렬 작업명세서

작성일: 2026-09-23. 상태: 구현 착수용 설계 기준, 구현·런타임 검증 전.

관련 문서: [초기 기획](Minecraft_JARVIS_PLAN.md), [검토 결과 및 공식 근거](Minecraft_JARVIS_REVIEW.md).

## 1. 고정 요구사항과 릴리스 범위

- 최소 Minecraft **1.21.8**. 첫 공식 검증은 Paper/Fabric/NeoForge 각각 1.21.8, Java 21. `1.21.8 이상`은 이후 모든 버전의 바이너리 호환 보장이 아니다.
- 생성 LLM은 **OpenAI `gpt-6-luna`**, 요청 분류는 **TypeSafe AI Jev**. 다른 모델로 자동 교체하지 않는다.
- 초기 상호작용 주체는 현재 서버가 OP로 인정하는 **접속 중 플레이어**뿐이다. 콘솔·Discord·웹·비OP 권한 그룹은 대상에서 제외한다.
- 기존 공식 SDK, 플랫폼 이벤트·스케줄러, CoreProtect/WorldGuard/CMI 공개 API를 재사용한다.
- 구현자는 범위 밖 기능을 편의를 이유로 추가하지 않는다. 아래 디렉터리는 아직 없는 **생성 예정 경로**다.

| 단계 | 포함 | 완료 게이트 |
|---|---|---|
| G0 계약 확정 | 버전 매트릭스, 공통 DTO/Tool·오류·OP·통신 계약, 빌드 골격 | 계약 fixture와 최소 빌드 통과, 담당 파일 경계 확정 |
| v0.1 | 3개 플랫폼, OP 비공개 대화, Luna+Jev, 현재 서버·플레이어·월드 조회, 요청자 자기 텔레포트, 감사·장애 처리 | 세 플랫폼 공통 E2E + 두 모델 라이브 증거 |
| v0.1.1 | Paper CoreProtect 조회, WorldGuard 조회·보호 판정 | 플러그인 있음/없음/비호환/조회 실패 검증 |
| v0.2 | 검증된 CMI 추가 정보, 승인형 롤백, 영속 실행 저널 | preview 정확성·단일 실행·크래시 복구 증거 |
| 후속 | 사건 분석·시계열·알림·대시보드 | 별도 요구 정의 후 착수 |

v0.1에 rollback/warn/ban, 임의 커맨드·SQL·코드 실행, NPC, 자체 블록 이력/보호구역 엔진은 없다. Folia는 초기 Paper 지원에 포함하지 않는다. 원안 §24·29의 플러그인 전체 동시 완료 조건은 위 단계로 대체한다.

## 2. 책임 경계

```text
Minecraft Adapter
  OP 검사 → 호출/대화 감지 → 공개 전달 차단 → 인증된 로컬 연결
                                       ↓
Brain: 세션·예산 검사 → Jev 요청 분류 → Luna 응답/Tool 인자 생성
                                       ↓
Brain: 스키마·정책 검사 → Adapter: 현재 OP·범위·중복 재검사
                                       ↓
플랫폼 스케줄러 → 공식 API → 구조화된 결과·감사 → Luna → 요청 OP 비공개 응답
```

Brain은 localhost에 WebSocket 서버를 열고 Adapter가 접속한다. 구현은 기존 라이브러리를 사용한다. 공통 Java 모듈은 Bukkit/Fabric/NeoForge 및 NMS 클래스를 import하지 않는다. 플랫폼 객체는 DTO로 변환하고 스레드 밖에 보관하지 않는다.

### 모델 역할

| 구성 요소 | 맡는 일 | 맡지 않는 일 |
|---|---|---|
| Jev | `SERVER_QUERY / PLAYER_QUERY / WORLD_QUERY / HISTORY_QUERY / REGION_QUERY / ACTION_REQUEST / GENERAL / UNCERTAIN` 중 분류 | 문장·좌표 생성, 권한 결정, 승인 판정, 서버 변경 실행 |
| Luna | 후속 질문 해석, Tool 선택·인자 제안, 조회 결과 설명 | 데이터 없는 수치 생성, 결과 성공 추측, 코드·콘솔 실행 |
| 일반 코드 | OP/정책·범위 검증, 시간 계산, 예산, 실행, 승인 토큰, 감사 | 자체 모델·분류 엔진 재구현 |

직접 호출 별칭·TTL·종료 메시지는 코드로 판별한다. Jev에는 해당 OP의 최신 메시지, 짧은 대화 주제, 활성 capability만 보낸다. 전체 공개 채팅이나 원본 서버 로그를 넣지 않는다. 분류는 도구 후보를 좁히는 힌트이며 실행 권한을 부여하지 않는다.

Jev 기준 버전은 `jev-1.13.0`, 공식 SDK는 `@typesafe-ai/sdk`. 문장 생성은 공식 OpenAI SDK의 Responses API를 사용한다. 세부 버전·reasoning 지원값은 T00에서 고정한다. 한국어 평가 전 임의 confidence 수치를 안전 기준으로 확정하지 않는다.

Jev 저신뢰·장애 시: Luna에 제한된 **조회 전용** 도구를 제공하거나 재질문한다. 변경 도구는 제공하지 않는다. Luna 장애 시: 사실을 지어내지 않고 고정 장애 안내를 제공한다. 모델 접근 실패를 타 모델로 숨기지 않는다. Jev 사용을 제거한 상태는 v0.1 완료가 아니다.

## 3. 공통 동작 계약

### 3.1 OP와 채팅

1. 실제 인증된 플레이어 UUID와 서버 OP 상태를 사용한다. Brain/LLM이 보내는 `isOp=true`는 권한 근거가 아니다.
2. Paper는 서버의 OP 판정 API, Fabric/NeoForge는 서버 운영자 등록 정보를 기준으로 한다. 구체 API는 T00에서 1.21.8 대상으로 확인한다. 권한 레벨 또는 `jarvis.*` 노드만으로 비OP를 허용하지 않는다.
3. 접수 전, Tool 실행 직전, 결과 전달 직전 권한을 재검사한다. OP 해제·로그아웃 시 세션과 대기 변경을 폐기한다. 비동기 이벤트에는 플랫폼이 허용하는 안전한 권한 스냅샷만 사용하고, 실제 전송·실행은 재검사를 통과해야 한다.
4. 비OP 일반 채팅은 기존 동작을 유지하며 Brain·모델로 보내지 않는다. OP의 JARVIS 입력과 응답은 다른 플레이어에게 공개하지 않는다. 다른 플러그인의 로그까지 숨겨진다고 보장하지 않는다.
5. 별칭은 메시지 시작의 독립 호출어로 인식한다. `자비스`, `jarvis`, `재비스`; 영문 대소문자 무시. `자비스팅` 같은 부분 일치는 제외한다.
6. 직접 호출 후 120초 동안 후속 대화를 받는다. 타이머는 수락한 대화로 갱신한다. `대화 끝`으로 종료, `!내용`은 해당 메시지만 일반 채팅으로 보내며 JARVIS에는 보내지 않는다. 이 규칙을 대화 시작 시 짧게 알려준다.
7. 세션 키는 `(serverId, requesterUuid, sessionId)`. 세션별 요청은 직렬화하고 사용자·서버 사이 맥락을 섞지 않는다. 대상 플레이어는 UUID로 유지하고 이름이 모호하면 질문한다.
8. 응답은 일반 텍스트로 안전하게 렌더링한다. LLM 문자열을 MiniMessage나 실행 가능한 클릭 명령으로 해석하지 않는다.

### 3.2 Tool 계약

공통 결과는 `status, data, error, observedAt, source, truncated`를 가진다. 성공/실패와 데이터 없음/미지원을 구분한다. 모든 Tool은 엄격한 입력 스키마와 범위 제한을 갖는다.

| Tool | 주요 입력 | 결과/제약 |
|---|---|---|
| `get_server_status` | 없음 | 지표 값·단위·측정 구간·시각, 미지원 값 null |
| `get_online_players` | cursor, limit | UUID/이름 목록, 상한 100, cursor와 truncated |
| `get_player` | UUID 또는 정확한 이름 | 서버에서 확인한 단일 플레이어, 모호/미존재 오류 |
| `get_player_location` | playerUuid | 서버/월드 식별자, 좌표, observedAt; 오프라인이면 오류 |
| `get_nearby_players` | center, radius, limit | 같은 월드, 반경 상한 64블록, 최대 100명 |
| `get_world_info` | worldId | 로드된 월드의 지원 가능한 정보만 |
| `teleport_staff` | targetPlayerUuid, actionId | 이동 주체는 항상 requester; 온라인 대상 위치를 실행 직전 확인 |

`teleport_staff`는 사용자가 실제 이동을 요청한 턴에서만 제안한다. 단순 위치 질문에는 실행하지 않는다. 모호한 대상은 먼저 질문하고, 임의 좌표·다른 플레이어 강제 이동·오프라인 이동은 초기 미지원이다. 이동 대상 월드·경계·플랫폼 취소 결과를 확인하고 실제 성공 후에만 완료 응답을 보낸다. 장애 fallback에서는 실행하지 않는다.

History/Region 조회는 v0.1.1 Tool catalog에 추가한다. 이력 기본 범위는 10블록/30분, 최대 64블록/24시간/100개 결과다. `since`는 Adapter의 신뢰할 수 있는 현재 시각을 기준으로 UTC 시작·끝으로 고정한다. 전체 건수와 반환 건수를 혼동하지 않는다. `source=CoreProtect`라도 누가 그 행동을 의도했는지 추정하지 않는다.

### 3.3 스레드와 부하

- AI·WebSocket·디스크·DB I/O를 tick 스레드에서 기다리지 않는다. 서버 API는 해당 플랫폼의 요구 스레드에서만 호출한다.
- 조회 Provider가 별도 비동기 실행을 요구하면 그 API 계약을 따른다. 단순히 모든 Tool을 worker에 넣는 구현은 허용하지 않는다.
- 서버 스레드에서는 제한된 스냅샷 획득·최종 변경만 수행한다. 무제한 청크 로드·전체 월드 순회는 금지한다.
- 초기 설정 제안: 세션당 진행 1건/대기 2건, 서버당 진행 대화 4건, 전체 대기 16건, 요청당 Tool 8회·모델 왕복 4회·총 30초. 초과 시 `BUSY` 또는 예산 초과로 종료한다.
- Jev 3초, 일반 조회 Tool 5초, 전체 대화 30초를 초기 deadline으로 사용하되 성능 검증 후 조정한다. 네트워크 timeout은 서버 변경 취소를 증명하지 않는다.
- 동일 부하에서 JARVIS off/on의 tick MSPT p50/p95, queue 길이, 모델별 지연·호출량을 기록한다. 초기 합격 기준은 p95 MSPT 증가 5ms 이하이며, 기준 부하·장비·측정 구간을 함께 고정한다.

### 3.4 통신·재시도

공통 envelope: `protocolVersion, type, messageId, requestId, serverId, sessionId, requesterUuid, sentAt, deadlineAt, payload`. Tool 메시지에는 별도 `toolCallId`, 변경에는 `actionId`가 있다. 스키마 외 필드·과대 메시지(초기 64KiB 상한)·미등록 Tool을 거부한다.

메시지 종류: `hello`, `capabilities`, `chat.message`, `chat.response`, `tool.request`, `tool.result`, `cancel`, `error`, `ping/pong`. 상세 required 필드와 예제는 T01 산출물로 확정한다. 인증 전에는 hello 외 업무 메시지를 처리하지 않는다.

- loopback 바인딩과 서버별 공유 비밀 인증. 비밀은 handshake 헤더로 전달하고 URL/감사 로그에는 남기지 않는다. 샘플 비밀·빈 비밀은 기동 오류다. 비밀 값은 외부 모델로 보내지 않는다.
- 인증된 연결에 serverId를 결합한다. actor/session은 Adapter가 등록한 요청과 일치해야 하며 Brain이 임의 actor를 새로 만들 수 없다.
- 프로토콜 major 불일치는 연결 거부. 재접속 시 capability를 다시 교환하고 이전 세션의 대기 실행을 폐기한다. 상태 변경은 재전송하지 않는다.
- 조회는 deadline 안에서 제한 재시도 가능. SDK 재시도와 앱 재시도를 중첩해 호출을 증폭하지 않는다.
- Adapter가 actionId별 상태를 보관해 연결 내 중복 실행을 막는다. 프로세스 재시작 후 이전 세션 요청은 거부한다. ACK 누락 시 `OUTCOME_UNKNOWN`이며 성공/실패로 단정하거나 자동 재실행하지 않는다.
- 오류 코드: `UNAUTHORIZED`, `INVALID_ARGUMENT`, `UNSUPPORTED`, `NOT_FOUND`, `AMBIGUOUS_TARGET`, `BUSY`, `TIMEOUT`, `PROVIDER_UNAVAILABLE`, `CANCELLED`, `OUTCOME_UNKNOWN`, `INTERNAL`. API 키·stack trace는 플레이어 응답에 넣지 않는다.

### 3.5 보안·설정·감사

- Brain 환경 변수: `OPENAI_API_KEY`, `TYPESAFE_API_KEY`. Minecraft 설정에는 모델 API 키를 저장하지 않는다. 로컬 공유 비밀은 접근 제한한 설정 또는 환경 변수로 주입한다.
- 기본 정책 `op-only=true`, `reply=requester-only`, rollback/warn=false. 초기 릴리스에는 OP 제한을 우회하는 옵션을 제공하지 않는다.
- capability는 플러그인 존재만으로 true가 아니다. 버전·필수 API·초기화 상태를 확인한다. 실행 때에도 재검사하고 장애 Provider는 도구 목록에서 제거한다.
- 외부 텍스트·닉네임·기록 내용은 데이터다. 그 안의 지시를 정책으로 승격하지 않는다. 최종 allowlist와 인자 검사는 Adapter에서도 수행한다.
- 모델에는 요청 해결에 필요한 위치·플레이어·도구 결과만 전달한다. IP·토큰·개인 채팅·전체 로그는 기본 제외한다. 운영 문서에 OpenAI와 TypeSafe로의 전송 항목을 명시한다.
- 감사 필드: timestamp, serverId, requesterUuid, requestId, toolCallId/actionId, tool, risk, 검증된 인자 요약, outcome, source, latency, 모델 ID, fallback 사유. 비밀·전체 프롬프트는 저장하지 않는다.
- v0.1 감사는 비동기 회전 JSONL, 기본 7일/총 100MiB. 변경 실행은 pre-execution 감사 기록을 안전하게 큐/저장할 수 없으면 거부한다. 큐 포화·디스크 오류를 무시하지 않고 health에 표시한다.

## 4. 병렬 작업 단위

T00/T01/T02는 공통 기반이므로 순서대로 병합한다. 이후 각 작업자는 동결된 계약과 fixture로 진행하며 다른 담당 디렉터리를 수정하지 않는다. 공유 API가 필요하면 T01 소유자에게 변경 요청하고 계약 버전을 함께 갱신한다.

| ID | 작업명 / 담당 경로 | 선행 | 산출물·완료 조건 |
|---|---|---|---|
| T00 | 호환성·재사용 조사 / `docs/compatibility.md`, `docs/decisions/` | 없음 | 1.21.8별 로더/API/Java/Gradle·라이선스·SDK 버전표, 공식 출처, API 스레드 조건, 미확인 항목. SDK 실제 접근은 별도 live 표시. |
| T01 | 계약·스키마 / `protocol/**`, `docs/protocol.md`, `docs/tools.md` | T00 | envelope·오류·Tool catalog·DTO·상태 전이·OP 정의, Java/TS 공용 JSON fixture, 유효/무효 예제. 세 플랫폼 담당이 계약 검토. |
| T02 | 빌드 골격 / 루트 Gradle·wrapper·version catalog, `brain/package*.json`, `brain/tsconfig.json`, `.github/**` | T01 | 세 플랫폼 모듈과 Brain 최소 빌드, 의존성 lock·schema 검사·CI 진입점. 모듈별 build 파일은 골격 병합 후 각 담당에게 인계. |
| T03 | Java 공통 실행부 / `minecraft/common/**` | T02 | DTO/transport·인증·deadline·중복 방지·Tool registry·scheduler SPI. 플랫폼 import 0건, fixture·재접속·가짜 scheduler 테스트 통과. |
| T04 | Brain 세션·정책·라우터 / `brain/src/core/**`, `brain/test/core/**` | T02 | OP actor/session binding, TTL·예산, serial queue, Tool allowlist, 모델·감사 인터페이스 조립. 가짜 모델·Adapter로 상태 전이 검증. |
| T05 | Luna·Jev 연동 / `brain/src/ai/**`, `brain/test/ai/**`, `evals/**` | T02 | 공식 SDK wrapper, Jev 분류, Luna Responses Tool loop, timeout/fallback, 한국어 평가·라이브 검증 스크립트. 임의 모델 교체 없음. |
| T06 | Paper Adapter / `minecraft/paper/**` 중 `integrations/` 제외 | T02 | OP/채팅/비공개 응답·조회·자기 텔레포트·스레드 매핑. T03 mock으로 개발, 실제 통합은 T03 이후. |
| T07 | Fabric Adapter / `minecraft/fabric/**` | T02 | T06과 동일한 공통 계약, Fabric 서버 이벤트·권한 등록·scheduler 매핑. 클라이언트 전용 API 의존성 없음. |
| T08 | NeoForge Adapter / `minecraft/neoforge/**` | T02 | T06과 동일한 공통 계약, NeoForge 이벤트·권한 등록·scheduler 매핑. 전용 서버 기동 확인. |
| T09 | 감사·설정·운영 / `brain/src/ops/**`, `brain/test/ops/**`, `config/**`, `docs/operations.md` | T02 | 키 검증, JSONL 회전·마스킹·health, 운영 예제, 시작/종료/장애 안내. 공통 Java 감사 이벤트는 T03 인터페이스를 소비. |
| T10 | 공통 수용 테스트 / `tests/acceptance/**`, `docs/verification/**` | T02; 실행은 T03~T09 | 재현 가능한 서버 fixture·동일 시나리오·부하 기준. 플랫폼별 E2E 증거와 모델 live 증거를 구분해 v0.1 판정. |
| T11 | CoreProtect 조회 / `minecraft/paper/.../integrations/coreprotect/**` | T01,T02; 연결은 T03,T06 | 공개 API 조회·범위·페이징·부분 결과. 없음/버전 불일치/빈 결과/timeout 계약 테스트 및 Paper 통합. |
| T12 | WorldGuard 조회 / `minecraft/paper/.../integrations/worldguard/**` | T01,T02; 연결은 T03,T06 | 위치 Region·flags·보호 query. priority/parent/member/bypass 사례, WorldEdit 의존성 확인. |
| T13 | Paper Provider 조립·v0.1.1 수용 / `minecraft/paper/.../IntegrationRegistry.*`, `tests/integrations/**` | T10,T11,T12 | T06 담당에게 해당 registry 파일 소유권 인계받아 연결. 설치 조합별 선택 로딩·미지원 도구 비노출, 조회 실제 증거. |
| T14 | CMI 공개 API 검증·선택 확장 / `minecraft/paper/.../integrations/cmi/**` | T13 | 지원 가능한 고유 정보만 채택, API·라이선스·재배포 조건 확인. warning은 별도 범위 승인 전 제외. |
| T15 | 승인형 롤백 / `brain/src/approvals/**`, `tests/rollback/**`, 계약 변경 요청 | T13 + 후속 범위 착수 | 아래 승인 계약과 CoreProtect preview/execute 동일 범위 검증. T11 소유자가 Provider 변경, T01 소유자가 schema 변경. |

각 구현 작업은 해당 경로 내 단위·계약 테스트도 소유한다. `...`는 T02에서 고정할 Java package 경로이며, T01에서 공통 인터페이스에 맞는 정확한 위치를 지정한다. package 경로 확정 전 임의로 중복 구조를 만들지 않는다.

### 공통 파일 충돌 방지

- T02 병합 이후 루트 빌드·lockfile·CI는 통합 담당 1명만 수정한다. 각 작업자는 필요한 의존성·설정 변경을 요청한다.
- T04 모델 인터페이스와 T05 구현 인터페이스는 T01에서 먼저 고정한다. T09 audit sink도 동일하다. 서로의 구현 완료를 기다리지 않고 fake를 사용한다.
- Provider registry 변경은 T13 전담. T11/T12는 Provider와 테스트만 만든다. 공통 문서 변경은 통합 담당이 반영한다.
- 작업별 브랜치 예: `codex/t05-ai-routing`. 각 작업자는 선행 계약 커밋, 변경 파일, 실행 검증, 남은 한계를 인계서에 기록한다.
- 동결 이후 호환 파괴 변경은 소비자 영향 목록·새 fixture·버전 변경을 함께 제출한다. 다른 작업자의 임시 파일·dirty 변경을 정리하지 않는다.

### 4개 작업 슬롯 운영 예시

| 구간 | 슬롯 A | 슬롯 B | 슬롯 C | 슬롯 D |
|---|---|---|---|---|
| 기반 | T00 → T01 → T02 | 기반 검토 참여 | 기반 검토 참여 | 기반 검토 참여 |
| 1차 | T03 공통 Java | T04 Brain | T05 모델 | T09 운영 |
| 2차 | T06 Paper | T07 Fabric | T08 NeoForge | T10 테스트 작성·실행 준비 |
| 3차 | T11 CoreProtect | T12 WorldGuard | T10 공통 E2E | 통합·결함 수정 |
| 4차 | T13 Provider 통합 | 회귀 검증 | 운영 문서 | 릴리스 증거 정리 |

T11/T12는 공통 v0.1 검증과 병렬 개발할 수 있으나 릴리스 게이트는 별도다. 후속 T14/T15는 v0.1 구현 완료 조건에 섞지 않는다. 이 표는 실행 계획이며 이번 문서 작성에서 병렬 구현을 시작했다는 뜻이 아니다.

## 5. 후속 승인형 롤백 계약

- 서버 측 PendingAction에 serverId, requesterUuid, sessionId, actionId, 정규화 인자 hash, preview 출처·시각·범위, 만료 시각, 상태를 저장한다.
- `PENDING → EXECUTING → SUCCEEDED / FAILED / OUTCOME_UNKNOWN`, 또는 `PENDING → EXPIRED / CANCELLED`. 실행 전 토큰은 원자적으로 한 번만 소비한다.
- 승인 입력은 동일 OP·세션·action에 연결한다. 기본은 클릭 버튼/정해진 승인 토큰이다. 자연어 `응`만으로 처리하려면 대기 action이 정확히 하나이고 코드의 엄격한 승인 문법과 동일 범위 검증을 만족해야 한다. Jev/Luna의 긍정 분류만으로 승인하지 않는다.
- 인자·기간·preview가 바뀌면 재승인한다. 상대 시간은 고정 시간창으로 변환한다. backend가 preview와 같은 대상 집합을 실행한다고 보장하지 못하면 자동 롤백을 활성화하지 않는다.
- 이력 조회 건수와 실제 복구 가능 건수는 다를 수 있다. 범위·예상 변경·누락·한계를 먼저 표시한다. API가 비변경 preview를 지원하지 않으면 조회 요약을 정확한 preview로 위장하지 않는다.
- SQLite 등 기존 저장소에 실행 저널을 남기고 crash 후 `EXECUTING`은 자동 재실행하지 않는다. 부분 적용·결과 불명은 사람이 상태를 확인하도록 보고한다.
- 승인 TTL 초기 60초, rollback 기본 비활성화. backup·복구 절차·전용 테스트 월드에서의 실제 preview/execute 검증 후 활성화한다.

## 6. 수용 기준과 검증 증거

| ID | 시나리오 | 합격 조건 |
|---|---|---|
| A01 | OP 1명·비OP 1명·다른 OP 1명 | 비OP 입력 외부 전송 0건, OP 대화·응답은 요청자만 수신 |
| A02 | 진행 중 deop/로그아웃 | 후속 Tool 및 응답 거부, 변경 실행 0건, 세션 폐기 |
| A03 | 120초 연속 질문·만료·종료·공개 탈출 | 문맥 유지/삭제와 공개·비공개 라우팅이 계약대로 동작 |
| A04 | 동시 OP·동일 이름·서버 2개 | UUID/서버/세션 격리, 잘못된 대상 추측 0건 |
| A05 | 현재 상태·위치·자기 텔레포트 | 서버 직접 관측과 일치, 지표 단위/시각 존재, 이동 완료 후 성공 응답 |
| A06 | 프롬프트 주입·미등록 Tool·과대 인자 | Adapter 실행 거부; 커맨드·SQL·코드 실행 경로 없음 |
| A07 | Brain 종료·재접속·중복 요청·ACK 손실 | tick 중단 없음, 변경 재실행 0건, 불명 결과는 불명으로 표시 |
| A08 | Jev 429/timeout/저신뢰, Luna 실패 | 조회 전용 fallback 또는 재질문, 변경 0건, 모델 교체 없음 |
| A09 | 한국어 Jev 평가 | 최소 200개 라벨링 사례, class별 결과·혼동행렬·abstention 보고, 홀드아웃 route 정확도 95% 이상을 초기 목표로 검증 |
| A10 | SDK·모델 라이브 | 실제 `gpt-6-luna` Tool call/result 응답 + Jev 모델 ID·분류 결과·request ID 기록; 키는 마스킹 |
| A11 | 지표 부하·queue 포화 | §3.3 부하 기준 충족, 대기열 상한 유지, 초과는 명시적으로 거절 |
| A12 | Provider 없음·실패·불완전 데이터 | 도구 비노출/명시 오류, 빈 결과를 전체 무사고로 해석하지 않음 |
| A13 | 감사 디스크 실패·회전 | 비밀 노출 0건, 무한 증가 없음, 변경은 기록 불능 시 거부 |
| A14 | 롤백 승인·만료·중복·다른 OP·재시작 | v0.2 전용: 무승인/중복 실행 0건, preview 불일치 시 중단 |

A09에는 존댓말·반말·오타·부정문·지시대명사·복합 요청·일반 대화·공격 문자열을 포함한다. confidence 임계값은 개발셋에서 정하고 홀드아웃을 튜닝에 재사용하지 않는다. 95%는 관측 성능이 아닌 제품 목표다. 평가 미달이면 자동 변경 경로를 열지 않고 v0.1 모델 게이트를 미완료로 남긴다. 무권한 실행 0건은 모델 정확도가 아닌 결정적 정책 테스트로 보장한다.

검증은 다음 네 층으로 나누어 기록한다.

1. 빌드·단위·계약: 각 모듈 컴파일, schema fixture, fake 모델/서버로 상태 전이 검증.
2. 실제 서버: Paper/Fabric/NeoForge 1.21.8 각각 기동·이벤트·권한·API 동작.
3. 실제 클라이언트: OP/비OP/다른 OP로 접속해 채팅 노출·후속 대화·텔레포트 확인.
4. 외부 모델 live: 두 공급자 호출 및 서버 Tool 결과를 거쳐 최종 응답까지 같은 requestId로 연결.

mock 통과를 외부 모델 성공으로, 서버 기동을 클라이언트 E2E로 보고하지 않는다. 키·비용 승인·테스트 서버 준비가 안 되면 가능한 로컬 검증을 완료하고 live 미검증을 명시한다. 이번 문서 작성에서는 유료 API 호출이나 서버 실행을 하지 않는다.

## 7. 착수·인계 체크리스트

- [ ] T00 버전·공개 API·라이선스·스레드 조건이 출처와 함께 기록되었다.
- [ ] T01 계약 버전과 정상/실패 fixture가 고정되었다.
- [ ] T02 기본 빌드·lockfile과 정확한 Java package 경로가 병합되었다.
- [ ] 각 작업의 담당 경로·선행 커밋·출력 인터페이스가 명시되었다.
- [ ] 병합 전 관련 모듈 검증과 계약 호환 검사 결과가 있다.
- [ ] 릴리스별 실제 플랫폼·클라이언트·모델 증거와 미검증 항목이 분리되었다.
- [ ] v0.1에서 OP 전용, 비공개 응답, 두 모델 사용, 임의 실행 금지가 확인되었다.

당장 시작할 작업은 **T00 → T01 → T02**다. 그 후 T03/T04/T05/T09를 병렬화한다. 문서 검토만 완료된 현재 상태에서는 위 구현 체크박스를 완료 처리하지 않는다.
