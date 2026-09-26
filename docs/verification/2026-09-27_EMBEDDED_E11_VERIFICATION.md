# 2026-09-27 Embedded Brain E11 Live Verification

## 범위

`docs/JarvisMinecraft_EMBEDDED_BRAIN_SIMPLIFIED_PLAN.md`의 **Phase E11 (Live Jev/Luna 통합)** 을 검증한다.

- TypeSafe Jev 모델 (`jev-1.13.0`, HTTPS endpoint: `https://api.typesafe.ai/v1/systemone`) 실제 라이브 호출
- OpenAI Luna 모델 (`gpt-6-luna`, OpenAI Responses API 공식 Java SDK) 실제 라이브 멀티턴 도구 호출
- JVM 내부 `EmbeddedBrain` + `EmbeddedBrainGateway` + `CommonRuntime` + `StandardMinecraftToolService` 통합 파이프라인
- `AsyncJsonlAuditSink` 실시간 파일 기록 및 Pre-execution audit fail-closed 계약
- 시크릿(API 키) 안전 로드 및 마스킹 준수 (콘솔, 감사 로그, 코드 내 키 값 노출 금지)

---

## 실행 환경 및 검증 구성

- **OS / Runtime**: Windows 11, Java 21 (JDK 21.0.7), Gradle Wrapper 8.14.5
- **SDK**: OpenAI Java SDK `4.69.2`, TypeSafe SystemOne Jev HTTP 클라이언트
- **검증 러너**: `EmbeddedBrainLiveVerificationMain.java`
- **실행 태스크**: `./gradlew :minecraft:common:embeddedBrainLiveVerification`

---

## 검증 결과 요약

| 검증 시나리오 | 입력 텍스트 | Jev 분류 (Latency) | Luna Tool Proposal (Latency) | Minecraft Tool 실행 | 최종 자연어 응답 (Latency) | 전체 소요 시간 | 결과 |
|---|---|---|---|---|---|---|---|
| **시나리오 1: 서버 조회** | `자비스 서버 상태 알려줘` | `SERVER_QUERY` (470ms, conf=1.0) | `get_server_status` (3,022ms) | `serverStatus()` (TPS 20.0, MSPT 14.2ms) | 서버 상태 요약 전달 (1,458ms) | 5,060ms | **PASS** |
| **시나리오 2: 관리자 텔레포트** | `자비스 나를 Steve한테 보내줘` | `ACTION_REQUEST` (222ms, conf=0.86) | 1턴: `get_player` (1,202ms)<br>2턴: `teleport_staff` (1,046ms) | Pre-audit 통과 후 `teleportRequesterTo` 실행 | 이동 완료 안내 전달 (751ms) | 3,331ms | **PASS** |

---

## 세부 검증 내역

### 1. 시나리오 1: 서버 상태 조회 (Read-only Query)

- **입력**: `"자비스 서버 상태 알려줘"`
- **Jev 호출**:
  - 분류 카테고리: `SERVER_QUERY`
  - 신뢰도(Confidence): `1.0`
  - 소요 시간: `470ms`
- **RoutePolicy**:
  - `get_server_status`, `get_online_players` 등 읽기 전용 도구 노출 확인
  - 상태 변경 도구(`teleport_staff`) 차단 확인
- **Luna 도구 제안 (Turn 1)**:
  - 제안 도구: `get_server_status` (`NoArguments`)
  - ToolArgumentCodec strict validation 통과
  - 소요 시간: `3,022ms`
- **CommonRuntime 도구 실행**:
  - `ServerStatusSnapshot` (TPS: 20.0, MSPT: 14.2ms, 플레이어: 2명, 청크: 450개, 메모리: 512MiB/2GiB) 반환
- **Luna 최종 턴 (Turn 2)**:
  - 도구 결과 반영 한국어 최종 응답 생성:
    > "현재 서버 상태입니다.\n- TPS: 20.0\n- MSPT: 14.2ms\n- 접속 인원: 2명\n- 로드된 청크: 450개\n- 메모리 사용량: 약 512MiB / 2GiB"
  - 소요 시간: `1,458ms`
- **플랫폼 전달**:
  - `LivePlatform.sendPrivatePlain()` 호출 확인

---

### 2. 시나리오 2: 관리자 텔레포트 (State-changing Action)

- **입력**: `"자비스 나를 Steve한테 보내줘"`
- **Jev 호출**:
  - 분류 카테고리: `ACTION_REQUEST`
  - 신뢰도(Confidence): `0.86`
  - 소요 시간: `222ms`
- **RoutePolicy**:
  - `get_player`, `get_player_location`, `teleport_staff` 노출 확인
- **Luna 도구 제안 (Turn 1 & 2)**:
  - **Turn 1**: `get_player` 호출 제안 (Steve 플레이어 정보 사전 조회) -> 소요 시간 `1,202ms`
  - **Turn 2**: Steve가 대상임을 확인하고 `teleport_staff` 호출 제안 (인자: target Steve UUID/Name) -> 소요 시간 `1,046ms`
- **Pre-execution Audit**:
  - 상태 변경 도구 실행 전 `AsyncJsonlAuditSink`에 `PRE_EXECUTION` 이벤트 기록 성공 확인
- **CommonRuntime + Platform 권한 재검사 및 실행**:
  - 요청자 OP 권한 재확인 통과
  - `LivePlatform.teleportRequesterTo(requester, steve)` 정확히 1회 실행 확인
- **Luna 최종 턴 (Turn 3)**:
  - 도구 결과 반영 한국어 최종 응답 생성:
    > "Steve에게 이동했어요."
  - 소요 시간: `751ms`
- **플랫폼 전달**:
  - 사용자에게 정상 메시지 전달 확인

---

### 3. 감사 로그 (Audit JSONL) 검증

- **파일 위치**: 임시 검증 디렉토리 내 `*.jsonl`
- **기록 이벤트 확인**:
  - `get_server_status` (Post-execution audit, outcome: `OK`)
  - `teleport_staff` (Pre-execution audit, outcome: `PRE_EXECUTION`)
  - `teleport_staff` (Post-execution audit, outcome: `OK`)
- **시크릿 마스킹 검증**:
  - 감사 로그 전체에서 실제 API 키 패턴(`sk-...`, `apikey-...`)이 전혀 포함되지 않음 확인
  - `AuditMasker`에 의한 민감 정보 마스킹 정상 준수

---

## 결론 및 다음 단계

- **E11 달성**: Fake/Stub이 아닌 실제 TypeSafe Jev 및 OpenAI Luna Responses API를 통한 JVM Embedded Brain 오케스트레이션이 완벽히 검증되었다.
- **다음 단계 (E12)**:
  - 기존 Node.js Remote Brain과 JVM Embedded Brain 간의 동일 fixture 대상 deterministic policy parity 비교 검증 진행.
