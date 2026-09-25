# ADR-0005: Jev + Luna 이중 모델 라우팅과 권한 경계

- 상태: Accepted
- 일자: 2026-09-26
- 관련 작업: Phase 6–7 refactoring
- 관련 ADR: [ADR-0002](0002-ai-sdk-model-pins.md), [ADR-0003](0003-platform-thread-and-op-boundary.md)

## 맥락

JARVIS Brain은 TypeSafe Jev와 GPT-6 Luna를 함께 사용한다. 두 모델을 단순 fallback 관계로 보거나 Jev confidence를 실행 권한으로 해석하면, 분류와 권한 집행의 경계가 흐려진다.

또한 Brain의 `RemoteAdapter`는 Minecraft 서버 API를 직접 호출하지 않는다. 따라서 Brain이 확인할 수 있는 것은 현재 WebSocket 연결에 등록된 requester/request/session binding의 유효성이지, Minecraft가 현재 해당 플레이어를 OP로 인정하는지 자체는 아니다.

## 결정

Jev + Luna 이중 모델 구조를 유지한다.

~~~text
OP input
  -> Jev classification
  -> deterministic route policy / Tool narrowing
  -> Luna response + Tool proposal
  -> Brain Tool/capability policy
  -> Adapter/CommonRuntime
  -> Minecraft server authority
~~~

책임을 다음처럼 고정한다.

### Jev

- 요청 의도를 분류하고 Luna에 노출할 Tool 후보를 좁힌다.
- confidence나 category는 권한, 승인, capability 또는 서버 상태의 증거가 아니다.
- timeout, 오류, 저신뢰, `UNCERTAIN`에서는 상태 변경 Tool 범위를 확대하지 않는다.

### Luna

- 사용자 응답과 허용된 Tool 호출을 제안한다.
- Luna의 Tool 호출은 실행 명령이 아니라 비신뢰 제안이다.
- Brain allowlist/capability/argument policy와 Adapter 검사를 우회할 수 없다.

### Brain

- 세션, 요청 예산, route policy, Tool allowlist와 request binding을 관리한다.
- Brain의 AdapterPort 메서드는 `isRequestBindingActive`로 명명한다.
- 이 메서드는 requester/request/session이 현재 연결에 결합돼 있는지만 뜻하며 Minecraft OP 권한 판정을 보장하지 않는다.

### Adapter / CommonRuntime

- 현재 online + OP 여부의 최종 권위는 Minecraft Adapter다.
- Tool 실행 직전 capability, 현재 권한, actor/request binding, deadline, deduplication과 action 상태를 다시 검사한다.
- 상태 변경 결과가 불명확하면 `OUTCOME_UNKNOWN`으로 끝내고 자동 재실행하지 않는다.

## 실패 정책

- Jev 실패/불확실: read-only 범위 또는 재질문 경로. 상태 변경 Tool을 새로 허용하지 않는다.
- Luna 실패: 고정 실패 경로를 사용하고 사실·Tool 결과를 추측하지 않는다.
- Brain request binding 실패: 모델/Tool/응답 전달을 계속하지 않는다.
- Adapter 권한 실패: 서버 권위 기준으로 실행과 응답을 거부한다.

## 결과

장점:

- 분류 모델과 생성 모델의 역할이 명확하며 두 모델을 동시에 사용하는 현재 제품 의도를 보존한다.
- Brain의 binding 확인을 실제 Minecraft 권한 확인으로 과장하지 않는다.
- 모델 confidence나 출력이 권한 확대로 연결되는 것을 구조적으로 방지한다.

비용:

- 단일 모델 구조보다 provider 호출과 관측 지점이 많다.
- Jev route 품질과 Luna Tool 품질을 각각 평가해야 한다.
- 모델 또는 SDK 변경 시 두 단계의 회귀 검증이 필요하다.

## 변경 조건

Jev 또는 Luna 중 하나를 제거하거나 역할을 합치는 변경은 단순 리팩터링으로 처리하지 않는다. 평가 근거와 실패 정책을 포함한 새 ADR이 이 결정을 명시적으로 대체해야 한다.
