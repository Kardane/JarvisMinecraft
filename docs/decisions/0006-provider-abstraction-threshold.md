# ADR-0006: Optional Provider 공통화 임계값

- 상태: Accepted
- 일자: 2026-09-26
- 관련 작업: Phase 8 refactoring
- 관련 ADR: [ADR-0004](0004-paper-provider-boundary.md)

## 맥락

Paper optional Provider는 CoreProtect, WorldGuard, CMI를 공개 API 경계에서 연결한다.
리팩터링 과정에서 다음 공통 인프라 후보가 검토됐다.

- bounded worker pool + queue
- timeout/cancellation wrapper
- cursor/snapshot store
- Provider lifecycle/registration framework
- reflection 대체 ServiceLoader 또는 별도 Provider artifact

겉으로는 모두 Provider지만 실제 실행 모델은 동일하지 않다.

### CoreProtect

- 기록 DB/API 조회를 Minecraft primary thread에서 기다리지 않는다.
- 전용 bounded worker pool과 queue를 가진다.
- query timeout과 cancellation이 필요하다.
- paging cursor가 requester/session/query에 binding된 bounded snapshot cache를 사용한다.
- worker/timer resource를 소유하므로 lifecycle close가 필요하다.

### WorldGuard

- 현재 server execution context에서 synchronous read-only query를 수행한다.
- region/member/flag 결과를 bounded DTO로 변환한다.
- worker pool, timeout scheduler, cursor snapshot을 소유하지 않는다.

### CMI

- 현재 온라인 플레이어 profile을 synchronous read-only API로 조회한다.
- nickname/AFK 값만 제한해 반환한다.
- worker pool, timeout scheduler, cursor snapshot을 소유하지 않는다.

## 결정

현재 단계에서는 `BoundedAsyncExecutor`, `BoundedSnapshotStore` 같은 공통 Provider infrastructure를 추출하지 않는다.

CoreProtect의 executor/timeout/cursor 구현은 CoreProtect 도메인 안에 유지한다.
WorldGuard와 CMI를 같은 비동기 abstraction에 맞추기 위해 불필요한 queue, timer 또는 snapshot lifecycle을 추가하지 않는다.

공통화는 **두 번째 실제 사용처가 같은 실패 의미와 lifecycle을 요구할 때** 수행한다.

### bounded async infrastructure 추출 조건

다른 Provider가 다음을 모두 요구할 때 재검토한다.

1. 서버 thread 밖에서 실행해야 하는 blocking Provider API
2. bounded concurrency와 bounded queue
3. deadline과 Provider-specific timeout 중 더 짧은 값을 적용
4. timeout 시 future를 먼저 terminal result로 확정하고 worker cancellation을 best-effort로 수행
5. queue saturation을 명시적 `BUSY` 결과로 노출
6. Provider shutdown 시 owned executor를 정리

단순히 `CompletableFuture`를 반환한다는 이유만으로 추출하지 않는다.

### snapshot/cursor infrastructure 추출 조건

다른 Provider가 다음을 모두 요구할 때 재검토한다.

1. 첫 조회 결과를 bounded snapshot으로 보관
2. cursor가 snapshot id와 offset을 표현
3. cursor를 requester/session/query identity에 binding
4. TTL과 최대 snapshot 수를 모두 제한
5. stale/mismatched cursor를 `INVALID_ARGUMENT`으로 fail closed

페이지 번호만 필요한 Provider에는 이 abstraction을 적용하지 않는다.

### IntegrationRegistry

현재 reflection 기반 module loading을 유지한다.

reflection은 임의 내부 접근을 위한 것이 아니라 optional plugin API linkage를 핵심 Paper class loading 경계에서 격리하기 위한 것이다.
`IntegrationRegistry`는 staged `ToolRegistry`에 Provider Tool을 먼저 등록하고, module initialization과 전체 Tool 등록이 성공한 경우에만 main registry에 원자적으로 반영한다.

다음 조건 중 하나가 발생할 때 ServiceLoader 또는 별도 Provider artifact를 재검토한다.

- Provider 수가 늘어나 static module catalog 유지가 어려워짐
- optional API dependency가 Paper artifact의 build/linkage를 반복적으로 불안정하게 만듦
- Provider를 독립 배포/버전 관리해야 함
- reflection entrypoint signature 변경이 반복됨

현재 세 Provider만으로는 이 비용을 정당화하지 않는다.

## timeout ordering

Provider timeout은 terminal result를 먼저 확정한 뒤 worker cancellation을 수행해야 한다.
worker를 먼저 interrupt하면 worker가 정상/오류 결과를 timeout보다 먼저 complete하는 race가 발생할 수 있다.

CoreProtect implementation은 다음 순서를 따른다.

1. `TIMEOUT` 결과를 atomic completion으로 시도
2. timeout completion이 승리한 경우에만 worker future를 cancel
3. worker 결과가 이미 완료된 경우 timeout은 결과를 덮어쓰지 않음

## 결과

장점:

- 실제로 다른 execution semantics를 가진 Provider를 하나의 framework에 강제로 맞추지 않는다.
- CoreProtect의 복잡성이 필요한 곳에만 남는다.
- WorldGuard/CMI의 synchronous path가 불필요하게 복잡해지지 않는다.
- 두 번째 사용처가 생겼을 때 필요한 API를 실제 요구사항에서 설계할 수 있다.

비용:

- CoreProtect 내부에 executor/snapshot 구현 코드가 당분간 남는다.
- 향후 같은 패턴의 Provider가 추가되면 그 시점에 안전하게 이동하는 리팩터링이 필요하다.

## 검증 경계

- T11: CoreProtect bounds, paging/cursor binding, timeout, partial result, Tool registration
- T12: WorldGuard bounds/protection query semantics
- T13: optional dependency matrix, fail-closed loading, atomic Tool registration, provider shutdown
- T14: CMI online profile contract

이 검증들은 서로 다른 Provider semantics를 의도적으로 유지한다.
