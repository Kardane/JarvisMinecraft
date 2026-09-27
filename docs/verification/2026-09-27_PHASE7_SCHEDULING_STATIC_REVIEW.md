# Phase 7 scheduling static review

작성일: 2026-09-27  
대상 브랜치: `codex/phase1-config-foundation`

## 구현 범위

- `SchedulingPolicy`
- `ScheduledActionService`
- `schedule_action`
- `cancel_scheduled_action`
- one-shot delay 1~60초
- repeating interval/duration, duration 최대 60초
- actor별 pending schedule 취소
- Brain shutdown 시 전체 pending schedule 취소
- protocol schema/fixture 동기화
- Jev/Luna에 `action.schedule` virtual capability 노출

## 예약 가능한 Action

```text
teleport_staff
weather_set
time_set
```

다른 Tool은 schedule registration 단계에서 거부한다.

## 실행 안전 경계

등록 시:

```text
scheduling enabled
→ nested Tool active
→ ExecutionPolicy allow
→ control Tool pre-audit
→ scheduleId 즉시 반환
```

실제 run 시:

```text
SchedulingPolicy 재확인
→ nested Tool active 재확인
→ ExecutionPolicy 재확인
→ 새 toolCallId/actionId/deadline
→ pre-execution audit
→ 정책 재확인
→ CommonRuntime
→ current online OP 재확인
→ platform API
```

반복은 이전 실행 completion 뒤에 다음 실행을 잡으므로 중첩 실행하지 않는다.
성공/EMPTY 이외 결과, 예외, policy revoke는 남은 반복을 중단한다.

## Lifecycle

pending schedule은 JVM memory에만 존재한다.

- actor cancel/logout/revoke -> actor schedule 취소
- Brain stop -> 전체 schedule 취소
- restart persistence 없음
- scheduler thread에서 Minecraft API 직접 호출 없음
- 실제 mutation은 기존 `CommonRuntime -> ServerScheduler` 경로 사용

## 검증 상태

사용자 요청에 따라 빌드/테스트는 실행하지 않았다.

미검증:

- Java/Gradle compile
- protocol fixture validator
- one-shot timing
- repeating serialization
- cancel race
- policy revoke during delay
- OP revoke during delay
- server shutdown cleanup
- Paper/Fabric/NeoForge clean boot

이 문서는 정적 검토 상태만 기록한다.
