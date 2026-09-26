# ADR-0007: Embedded Brain 전환과 JVM 내부 권한 경계

- 상태: Accepted
- 일자: 2026-09-26
- 관련 작업: Embedded Brain E0–E17
- 관련 ADR: [ADR-0003](0003-platform-thread-and-op-boundary.md), [ADR-0005](0005-jev-luna-routing-authority-boundary.md), [ADR-0006](0006-provider-abstraction-threshold.md)

## 맥락

현재 JarvisMinecraft는 Minecraft Adapter와 TypeScript Brain을 loopback WebSocket으로 분리한다. 이 구조는 Brain을 독립 프로세스로 운영하기 때문에 Node.js/npm 설치, Brain daemon 관리, 공유 비밀, hello/capabilities handshake, reconnect, ping/pong, 별도 process monitoring이 필요하다.

최종 배포 목표는 서버 관리자가 플랫폼별 JAR/MOD 하나와 모델 API 키만 준비하면 JARVIS를 사용할 수 있게 하는 것이다. 따라서 Brain의 세션·예산·분류·모델 orchestration을 JVM 안으로 옮기되, sidecar 구조에서 필요했던 process-boundary 상태를 Java 객체로 그대로 복제하지 않는다.

## 결정

1. production Brain은 최종적으로 JVM 안의 Embedded Brain으로 전환한다.
2. Jev와 Luna 이중 모델 구조는 유지한다.
3. Jev는 요청 분류 및 deterministic Tool narrowing에만 사용하며 권한 근거가 아니다.
4. Luna Tool call은 신뢰되지 않은 제안으로 취급한다.
5. Minecraft 권한과 실제 상태의 최종 authority는 계속 `CommonRuntime`과 플랫폼 Adapter에 둔다.
6. TypeScript Brain은 구조를 1:1 Java로 포팅하지 않고, 필요한 정책 불변조건만 이식한다.
7. Remote Brain은 Embedded parity가 확보될 때까지 reference implementation으로 유지한다.
8. AI HTTPS 호출은 비동기로 수행하며 Minecraft server/tick thread에서 기다리지 않는다.
9. 기존 Java runtime의 `ToolName`, `ToolRegistry`, `AuditSink`, `CommonRuntime`, `RequesterAuthority`, `DeadlinePolicy`, `DeduplicationLedger`, `ServerScheduler`를 재사용한다.
10. Tool argument 검증은 하나의 Java contract parser를 공유하고 AI 전용 validator를 중복 구현하지 않는다.

## 유지할 정책 불변조건

- 현재 접속 중인 OP만 요청을 수락한다.
- 실행 직전 current online OP를 다시 확인한다.
- request/session binding을 유지한다.
- 활성 Tool/capability allowlist를 유지한다.
- strict Tool argument validation을 유지한다.
- request deadline, Tool call budget, model round budget을 유지한다.
- session별 AI 요청을 직렬화하고 bounded queue를 유지한다.
- state-changing Tool은 pre-execution audit 성공 후에만 실행한다.
- state-changing 결과가 deadline 안에 확정되지 않으면 `OUTCOME_UNKNOWN`으로 남긴다.
- state-changing Tool은 자동 retry하지 않는다.
- deop/logout/session-end race에서 stale Tool 실행과 stale 응답 전달을 차단한다.
- optional Provider가 실제로 활성화되지 않으면 관련 Tool을 Luna에 노출하지 않는다.

## Embedded 목표 호출 흐름

```text
Minecraft Chat
  ↓
ChatSessionManager
  ↓
EmbeddedBrain
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
route / active Tool 확인
  ↓
pre-execution AuditSink
  ↓
CommonRuntime
  ↓
Platform Adapter authority/state recheck
  ↓
Minecraft API
  ↓
Tool result
  ↓
Luna final response
```

## 상태의 단일 소유권

### Session lifecycle

`ChatSessionManager`가 session ID, TTL, active 여부, 종료/invalidate를 소유한다.

Embedded Brain은 별도의 session TTL store를 두지 않는다. 모델 대화 이력만 `ConversationHistoryStore`에 저장한다.

### Tool metadata

`Protocol.ToolName`이 Tool의 정적 metadata인 wire name, capability, state-changing 여부, risk의 source of truth다.

`ToolRegistry`는 현재 서버에서 실제 등록된 Tool set을 소유한다. 별도 `BrainToolCatalog`은 만들지 않는다.

### Tool argument validation

Remote protocol과 Embedded Luna function call이 같은 `ToolArgumentCodec`을 사용한다. unknown/missing field, UUID, range, selector shape 검증을 별도로 복제하지 않는다.

### Audit

Embedded Brain은 기존 Java `AuditSink` contract를 사용한다. 별도 `AuditPort`를 만들지 않는다.

## Embedded에서 재현하지 않을 sidecar 상태

다음은 WebSocket/process boundary가 존재하기 때문에 필요한 상태다. Embedded 경로에는 새 abstraction으로 옮기지 않는다.

```text
Brain-side connection registry
serverId → connection map
authenticated adapter connection state
hello/capabilities handshake state
ping/pong
shared-secret authentication
reconnect generation
Brain-side AdapterPort
remote Tool result connection binding
```

Remote Brain이 존재하는 마이그레이션 기간에는 기존 구현을 유지하지만 Embedded 경로는 이에 의존하지 않는다.

## 단계적 전환

```text
BrainGateway seam
  ↓
공용 Java contract 정리
  ↓
Conversation history / budget / scheduler
  ↓
Jev / route / Luna
  ↓
EmbeddedBrain orchestration
  ↓
Remote/Embedded policy parity
  ↓
WebSocket 제거
  ↓
Node Brain 제거
```

Remote 삭제는 deterministic policy parity, 세 플랫폼 E2E, deop/logout race, audit fail-closed, `OUTCOME_UNKNOWN` no-retry가 검증된 이후에만 수행한다.

## 결과

### 장점

- 최종 운영자는 Node.js/npm/Brain daemon을 관리하지 않는다.
- process-boundary 전용 상태와 설정을 제거할 수 있다.
- Java에 이미 있는 authority, Tool registry, dedupe, deadline 정책을 재사용한다.
- session lifecycle, Tool metadata, Tool argument 검증의 이중 소유를 줄인다.

### 비용

- TypeScript Brain 정책을 Java로 의미 보존 이식해야 한다.
- Remote/Embedded parity 기간 동안 두 경로가 일시적으로 공존한다.
- Jev/Luna JVM client와 audit sink 구현이 필요하다.

## 비결정 사항

이 ADR은 OpenAI Java SDK의 최종 packaging 전략이나 Jev HTTP wire 세부사항을 고정하지 않는다. 해당 구현은 실제 Paper/Fabric/NeoForge classloader 검증 결과에 따라 후속 단계에서 결정한다.
