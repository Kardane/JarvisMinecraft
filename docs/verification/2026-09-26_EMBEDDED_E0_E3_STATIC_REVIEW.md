# 2026-09-26 Embedded Brain E0–E3 static review

## 범위

브랜치: `codex/embedded-brain-e0-e3`

기준: `main`의 `493772c6b85e4725e723a65bbcf66a1861fdca41`

이번 snapshot은 Embedded Brain 전환 계획의 E0–E3에 대한 **정적 소스 검토 결과**만 기록한다.

실제 Gradle build, verification task, dedicated server boot, 외부 모델 호출은 실행하지 않았다. 저장소 작업 지침에 따라 사용자가 별도로 실행 검증을 요청하지 않은 상태에서는 수행하지 않았다.

## 반영 범위

### E0 — Architecture Decision

- ADR-0007 추가
- Embedded Brain 채택
- Jev + Luna 유지
- `CommonRuntime / PlatformAdapter` final authority 유지
- TypeScript Brain 구조의 1:1 Java 포팅 금지
- sidecar/process-boundary 상태를 Embedded 경로에 재현하지 않음
- session / Tool metadata / Tool argument validation의 단일 소유권 명시

### E1 — BrainGateway seam

- 공통 `BrainGateway` 추가
- Remote `AdapterBrainConnection`이 `BrainGateway` 구현
- Paper/Fabric/NeoForge Brain connection wrapper가 `BrainGateway` 구현
- 세 플랫폼 chat/controller가 구체 Remote connection 대신 `BrainGateway`에 의존
- Fabric/NeoForge runtime state와 Paper entrypoint가 gateway 타입으로 Brain lifecycle을 보유

### E2 — 공용 Java contract 정리

- `ToolArgumentCodec` 추가
- 기존 `ProtocolCodec`의 Tool별 argument shape/range parser를 `ToolArgumentCodec`으로 이동
- Remote protocol decode가 공용 `ToolArgumentCodec`을 사용
- Tool metadata는 기존 `Protocol.ToolName`을 그대로 source of truth로 사용
- 기존 `AuditSink`를 유지하며 별도 Brain audit interface를 추가하지 않음

### E3 — ConversationHistoryStore

- `ConversationEntry` 추가
- `ConversationHistoryStore` 추가
- bounded `InMemoryConversationHistoryStore` 추가
- history key는 requester/session으로 격리
- history store는 session TTL, OP authority, active-session 판정을 소유하지 않음

## 정적 확인 결과

다음 항목을 branch source에서 확인했다.

- PASS: `AdapterBrainConnection implements BrainGateway`
- PASS: Paper chat layer는 `BrainGateway`를 사용하고 `PaperBrainConnection`에 직접 의존하지 않음
- PASS: Fabric chat layer는 `BrainGateway`를 사용하고 `FabricBrainConnection`에 직접 의존하지 않음
- PASS: NeoForge chat layer는 `BrainGateway`를 사용하고 `NeoForgeBrainConnection`에 직접 의존하지 않음
- PASS: `ProtocolCodec`은 `toolArguments.parse(tool, args)`로 Tool argument validation을 위임
- PASS: `ProtocolCodec`의 이전 private Tool argument parser 제거
- PASS: `ToolArgumentCodec`이 state-changing 및 optional-provider Tool argument를 포함
- PASS: conversation history는 bounded
- PASS: conversation history 구현에 TTL/OP authority 상태 없음
- PASS: `Protocol.ToolName`이 capability/stateChanging/risk metadata를 계속 소유
- PASS: 기존 `AuditSink` contract 유지

## 추가된 검증 코드

`T03VerificationMain`에 다음 contract 검증을 추가했다.

- Remote `AdapterBrainConnection`의 `BrainGateway` 구현 여부
- `ToolArgumentCodec` typed parsing
- unknown Tool argument field 거부
- nearby radius 범위 거부
- `ToolName` metadata source 확인
- conversation history bound
- session 간 history isolation
- actor 간 history isolation
- session clear
- actor clear

이 코드는 이번 작업에서 **실행하지 않았다**.

## 남은 검증

E0–E3 merge 전 또는 다음 검증 작업에서 최소 다음을 실행해야 한다.

```text
./gradlew :minecraft:common:check
./gradlew :minecraft:paper:check
./gradlew :minecraft:fabric:check
./gradlew :minecraft:neoforge:check
```

실제 실행 명령은 현재 저장소의 build 문서를 다시 확인한 뒤 사용한다.

E4 이후에는 `AiRequestScheduler`와 `RequestBudget`의 concurrency/deadline 검증을 별도로 추가해야 한다.
