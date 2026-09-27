# Phase 8 ACTIVE proactive static review

작성일: 2026-09-27  
대상 브랜치: `codex/phase1-config-foundation`

## 구현 범위

- `AmbientChatMessage`
- `AmbientConversationTracker`
- `BrainGateway.considerProactive`
- Jev `PROACTIVE_CANDIDATE` admission
- `START_CONVERSATION` confidence threshold
- server-wide proactive cooldown
- one in-flight proactive classifier
- 1초 minimum classification interval
- Paper/Fabric/NeoForge public-chat observation
- proactive requester session 생성
- `PROACTIVE` Luna turn
- proactive state-changing Tool hard block

## 흐름

```text
allowed public chat
→ normal public message preserved
→ bounded ambient tracker
→ asynchronous Jev(PROACTIVE_CANDIDATE)
→ START_CONVERSATION + threshold
→ server-thread policy recheck
→ requester session start
→ Luna(PROACTIVE)
→ public response
→ ordinary FOLLOW_UP session
```

## Fail-closed 조건

다음 경우 JARVIS가 먼저 말하지 않는다.

- PASSIVE mode
- audience denied
- requester already has active session
- server-wide proactive cooldown active
- classification interval active
- another proactive classification in flight
- Jev error/timeout/model mismatch
- engagement IGNORE/RESPOND
- engagement confidence below threshold
- ACTIVE/audience/session state changed before server-thread activation

## Tool boundary

`ExecutionPolicy`에서 PROACTIVE/PROACTIVE_CANDIDATE의 모든
`stateChanging=true` Tool을 제거한다.

따라서 OP proactive turn에서도 다음은 노출되지 않는다.

```text
teleport_staff
weather_set
time_set
schedule_action
cancel_scheduled_action
```

OP는 read-only Tool만 받을 수 있고, 비OP는 Minecraft Tool을 전혀 받지 않는다.

## UX

proactive turn은 assistant가 먼저 참여하므로 delayed progress/waiting message를
보내지 않는다. 최종 reply는 기존 public prefix와 requester-only sound 정책을
사용한다.

## 데이터/스레드 경계

ambient context는 JVM memory only이며 최대 50개 메시지만 유지한다. 실제 Jev
입력에는 config의 `context-messages` 개수만 사용한다. 메시지 text도 bounded
clip된다.

Minecraft server thread는 Jev network call을 기다리지 않는다. classification
completion 후 실제 session 생성은 다시 server scheduler에서 current policy를
확인한 뒤 수행한다.

## 검증 상태

사용자 요청에 따라 빌드/테스트는 실행하지 않았다.

미검증:

- Java/Gradle compile
- Jev live START_CONVERSATION behavior
- ACTIVE high-volume chat throttling
- cooldown race
- audience change during classification
- Paper async chat callback behavior
- Fabric/NeoForge clean-server ACTIVE smoke
- proactive read-only Tool use
- 기존 Phase 1~7 회귀

이 문서는 정적 코드 검토 상태만 기록한다.
