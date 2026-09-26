# 2026-09-26 Embedded Brain E4–E7 verification

## 범위

브랜치: `codex/embedded-brain-e0-e3`

E4–E7 구현 대상:

- E4: `RequestBudget`, `AiRequestScheduler`
- E5: JVM Jev classifier + conversation input projection
- E6: deterministic route/fallback policy
- E7: Luna contract, Tool schema adapter, OpenAI Responses Java client

이 단계는 Embedded Brain orchestration(E8) 이전의 foundation 구현이다. 현재 production chat 경로는 아직 Remote Brain을 사용한다.

## E4 — RequestBudget / AiRequestScheduler

추가:

- `RequestBudget`
  - generated contract의 Tool call/model round/request deadline 상한 재사용
  - Adapter deadline과 local 30초 deadline 중 더 이른 값 사용
  - deadline 만료 시 `TIMEOUT`
  - model/Tool budget 초과 시 `BUSY`
- `AiRequestScheduler`
  - JVM 하나 = Minecraft server 하나라는 전제로 server dimension 제거
  - global active 기본 4
  - session별 queued 기본 2
  - global queued 기본 16
  - requester/session별 active 1개
  - queued session/actor cancellation
  - shutdown 후 새 요청 거부
  - executor rejection 시 점유 slot 회수

`ServerScheduler`와 역할이 다른 AI request scheduler로 분리되어 있다.

## E5 — Jev JVM client

추가:

- `JevCategory`
- `JevInput`
- `JevClassification`
- `JevClassifier`
- `JdkJevClassifier`

고정:

```text
model   = jev-1.13.0
endpoint = POST https://api.typesafe.ai/v1/systemone
timeout = min(request remaining, 3 seconds)
retry   = 0
```

인증:

```text
Authorization: Bearer <TYPESAFE_API_KEY>
```

request ID는 공식 JS SDK와 동일하게:

```text
x-typesafe-request-id
```

에서 읽는다.

`JevInput.fromConversation`은 기존 TS policy와 동일하게:

- 최신 user message 선택
- tool entry 제외
- user/assistant short topic만 사용
- 최근 6개
- entry별 320자
- capability name projection

을 수행한다.

공식 근거:

- https://github.com/typesafe-ai/typesafe-sdk-js
- https://github.com/typesafe-ai/typesafe-sdk-js/blob/main/src/client.ts
- https://github.com/typesafe-ai/typesafe-sdk-js/blob/main/src/api-promise.ts

## E6 — DeterministicRoutePolicy

기존 TS route mapping을 JVM으로 이식했다.

정상 route는 Jev category와 현재 active Tool의 교집합만 Luna에 노출한다.

fallback:

```text
JEV_ERROR
JEV_UNCERTAIN
JEV_LOW_CONFIDENCE
```

에서는 active Tool 중 `stateChanging == false`인 Tool만 노출한다.

Jev 결과는 Tool narrowing에만 사용하며 Minecraft authority나 permission으로 사용하지 않는다.

## E7 — Luna JVM client

추가:

- `LunaTurnInput`
- `LunaStep`
- `LunaClient`
- `LunaPrompt`
- `LunaToolSchemas`
- `OpenAiLunaClient`

OpenAI 공식 Java SDK:

```text
com.openai:openai-java:4.69.2
```

를 compile-time pin으로 추가했다.

SDK runtime bundling/shading/relocation은 E16 packaging 단계에서 검증한다.

Responses request:

```text
model = gpt-6-luna
reasoning effort = medium
store = false
parallel_tool_calls = true
retry = 0
timeout = min(request remaining, 30 seconds)
```

`store=false` 상태에서 reasoning item을 다음 round로 안전하게 전달하기 위해
`reasoning.encrypted_content`를 include하고 returned reasoning item을 request-local state에 보존한다.

Tool function schema는 모델 출력 품질을 위한 presentation layer다.

권위 있는 argument validation은 별도로 구현하지 않고:

```text
Luna arguments JSON
  ↓
LunaToolSchemas alias mapping
  ↓
ToolArgumentCodec
  ↓
ToolArguments
```

경로를 사용한다.

따라서 `get_player_by_uuid` / `get_player_by_name` 같은 AI alias는 core `GET_PLAYER`로 변환되지만,
shape/range/UUID 검증은 Remote protocol과 같은 `ToolArgumentCodec`이 수행한다.

공식 근거:

- https://github.com/openai/openai-java/releases/tag/v4.69.2
- https://github.com/openai/openai-java
- https://central.sonatype.com/artifact/com.openai/openai-java

## deterministic verification 추가

`T03VerificationMain`에 다음 검증을 추가했다.

### RequestBudget

- Adapter deadline 우선
- model round 상한
- Tool call 상한
- deadline exact boundary timeout

### AiRequestScheduler

- global concurrency 제한
- same-session serialization
- queue bound
- queue overflow `BUSY`
- session cancellation
- shutdown cancellation

### Jev / routing

- model/timeout pin
- latest-user projection
- short-topic projection
- capability projection
- ACTION_REQUEST route subset
- low-confidence read-only fallback
- Jev error read-only fallback
- state-changing Tool fallback 미노출

### Luna

- `get_player` AI alias 2개 유지
- strict JSON schema의 `additionalProperties=false`
- alias → typed core Tool translation
- inactive Tool 거부
- conversation rendering
- model pin
- fallback instruction

## CI 증거

commit:

```text
d7afca19d2c6ec26f9ce22e06409c1de3456caf5
```

GitHub Actions CI run:

```text
run #146
id 36247238427
```

결과:

```text
Java / Gradle  SUCCESS
Brain / Node   SUCCESS
```

Java job의 `Build with committed dependency locks` 단계가 성공했다.

따라서 다음이 실제 CI에서 함께 검증되었다.

- Java 21 compilation
- OpenAI Java SDK API 사용 코드 compilation
- dependency lock 유효성
- 기존 platform module build
- `T03VerificationMain` 포함 Gradle verification
- 기존 Brain Node typecheck/build/test

외부 Jev/OpenAI live API 호출은 실행하지 않았다.

## 정적 source review

추가 정적 검토 결과:

- PASS: `RequestBudget`은 generated limits 사용
- PASS: `AiRequestScheduler`에 serverId dimension 없음
- PASS: scheduler key는 requester/session
- PASS: Jev model/timeout 고정
- PASS: Jev client에 application retry loop 없음
- PASS: TypeSafe request ID header가 `x-typesafe-request-id`
- PASS: Jev history projection parity
- PASS: fallback에서 state-changing Tool 제거
- PASS: Luna arguments가 공용 `ToolArgumentCodec` 사용
- PASS: inactive Luna Tool 거부
- PASS: 공식 OpenAI async client 사용
- PASS: Luna `store=false`
- PASS: reasoning `medium`
- PASS: stateless reasoning encrypted content round-trip
- PASS: SDK retry 0

## 남은 범위

E4–E7은 독립 구성요소 구현까지 완료했지만 다음은 아직 하지 않았다.

- `EmbeddedBrain` orchestration
- `BrainGateway` Embedded 구현
- CommonRuntime와 Luna Tool loop 실제 연결
- pre-execution audit 연결
- live Jev call
- live Luna function-call round trip
- Paper/Fabric/NeoForge Embedded mode wiring
- OpenAI SDK runtime bundling/shading
- Remote/Embedded parity E2E

다음 단계는 E8 `EmbeddedBrain` orchestration이다.
