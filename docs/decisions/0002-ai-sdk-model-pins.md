# ADR-0002: AI SDK 및 모델 버전 고정

- 상태: Accepted
- 일자: 2026-09-24
- 관련 작업: T00

## 맥락

JARVIS는 생성/Tool orchestration에 OpenAI GPT-6 Luna, 요청 분류에 TypeSafe AI Jev를 사용한다. alias 또는 범위 버전 의존성을 사용하면 모델/SDK 업데이트가 평가 결과와 Tool 동작을 소리 없이 바꿀 수 있다.

## 결정

T02/T05의 초기 기준을 다음과 같이 고정한다.

- Node.js: 24 LTS
- OpenAI JS SDK: 7.22.0
- OpenAI model: `gpt-6-luna`
- API: Responses API
- reasoning effort: `medium`
- TypeSafe SDK: `@typesafe-ai/sdk` 0.6.0
- TypeSafe model: `jev-1.13.0`

`jev-latest`처럼 이동 가능한 alias를 제품 평가 기준으로 사용하지 않는다. 두 공급자 중 하나가 실패해도 다른 모델로 자동 대체하지 않는다.

Jev는 분류만 담당한다.

- SERVER_QUERY
- PLAYER_QUERY
- WORLD_QUERY
- HISTORY_QUERY
- REGION_QUERY
- ACTION_REQUEST
- GENERAL
- UNCERTAIN

권한, 승인, 시간 계산, 서버 변경 여부는 모델 결과가 아니라 결정적 코드로 판정한다.

## 실패 정책

- Jev timeout/오류/저신뢰: 조회 전용 Luna 경로 또는 재질문. 변경 Tool은 제공하지 않는다.
- Luna 오류: 고정 장애 응답. 수치나 성공 결과를 추측하지 않는다.
- SDK retry와 애플리케이션 retry를 중첩하지 않는다.

## 결과

장점:

- A09/A10의 평가 결과를 특정 모델·SDK 조합에 귀속할 수 있다.
- alias 이동으로 분류 임계값이 무효화되는 위험을 줄인다.
- 모델 공급자 장애가 권한 확대나 자동 변경으로 이어지지 않는다.

비용:

- SDK 보안/호환 패치를 반영할 때 명시적인 버전 변경과 회귀 검증이 필요하다.

## 검증 상태

T00에서는 문서와 공개 패키지/릴리스를 확인했다. 실제 API 키를 사용한 유료 live 호출은 하지 않았다. `gpt-6-luna` Tool call/result와 `jev-1.13.0` request ID 증거는 T05/T10에서 별도로 남긴다.

## 공식 근거

- GPT-6 Luna: https://developers.openai.com/api/docs/models/gpt-6-luna
- OpenAI function calling: https://developers.openai.com/api/docs/guides/function-calling
- OpenAI Node SDK: https://github.com/openai/openai-node
- TypeSafe models: https://docs.typesafe.ai/models
- TypeSafe JavaScript SDK: https://docs.typesafe.ai/sdk/javascript
