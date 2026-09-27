# Phase 3 Jev engagement/reasoning static review

작성일: 2026-09-27  
대상 브랜치: `codex/phase1-config-foundation`

## 구현 범위

Phase 3에서 다음 AI decision/runtime policy를 추가했다.

- Jev `engagement` choice
- 기존 Jev `route` choice 유지
- Jev `reasoning` choice
- `ReasoningLevel = NONE / LOW / MEDIUM / HIGH`
- `ReasoningPolicy`
- Luna request별 dynamic reasoning effort
- Jev failure 시 configured reasoning fallback
- interaction origin을 Jev state에 전달

## Jev decision

한 Jev request가 다음 세 질문을 포함한다.

```text
engagement
  IGNORE
  RESPOND
  START_CONVERSATION

route
  SERVER_QUERY
  PLAYER_QUERY
  WORLD_QUERY
  HISTORY_QUERY
  REGION_QUERY
  ACTION_REQUEST
  GENERAL
  UNCERTAIN

reasoning
  NONE
  LOW
  MEDIUM
  HIGH
```

현재 production request origin은 `DIRECT` 또는 `FOLLOW_UP`이다.
Jev instructions는 이 두 origin에서 `RESPOND`를 선택하도록 요구한다.

`IGNORE`와 `START_CONVERSATION`은 미래 ACTIVE/proactive 경로를 위한 typed
signal이다. Phase 3에서 이미 admission된 direct/follow-up request를 drop하는
권한으로 사용하지 않는다.

## Reasoning precedence

```text
config mode != AUTO
  -> configured NONE/LOW/MEDIUM/HIGH

config mode == AUTO and valid Jev result
  -> Jev reasoning

config mode == AUTO and Jev failure/invalid model/output
  -> configured fallback
```

reasoning level은 Jev 이후 한 번 확정하며 Tool call/result로 이어지는 동일
Brain request의 모든 Luna round에서 바뀌지 않는다.

## OpenAI SDK mapping

프로젝트가 고정한 OpenAI Java SDK 4.69.2의 `ReasoningEffort`에 다음 값을
직접 매핑한다.

```text
NONE   -> ReasoningEffort.NONE
LOW    -> ReasoningEffort.LOW
MEDIUM -> ReasoningEffort.MEDIUM
HIGH   -> ReasoningEffort.HIGH
```

모델 ID는 계속 `gpt-6-luna`로 고정한다.

## 실패 경계

Jev HTTP error, timeout, malformed answer, unknown choice, model mismatch는
기존 route error fallback으로 들어간다.

- Tool route: active read-only Tool만
- reasoning: config fallback
- 모델 자동 교체 없음
- state-changing permission 확대 없음

valid `UNCERTAIN` route는 기존 deterministic route fallback을 사용한다.
이 경우 Jev request 자체는 유효하므로 reasoning choice는 그대로 사용할 수 있다.

## 호환성

기존 5-인자 `JevClassification` constructor는 다음 기본값으로 유지했다.

```text
engagement = RESPOND
reasoning = MEDIUM
```

기존 8-인자 `LunaTurnInput` constructor도 `MEDIUM` 기본값으로 유지했다.
기존 `EmbeddedBrain` constructor는 default `ReasoningPolicy`로 delegate한다.

production bootstrap은 shared `ConfigManager`를
`EmbeddedBrainGateway -> ReasoningPolicy`에 전달한다.

## 검증 상태

사용자 요청에 따라 테스트/빌드는 이번 Phase에서도 실행하지 않았다.

미검증 항목:

- Java/Gradle compile
- Jev multi-question live response shape
- OpenAI Responses API의 dynamic effort live call
- fixed reasoning config override
- AUTO + Jev reasoning propagation
- Jev error + fallback propagation
- 기존 E8-E16 회귀
- Paper/Fabric/NeoForge clean boot

이 문서는 정적 코드 검토 상태만 기록한다.
