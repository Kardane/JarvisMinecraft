# Operational Logging L3/L4 verification

날짜: 2026-09-27  
선행 구현: Operational Logging L1/L2

## 구현 범위

### Phase L3 — Tool / policy observability

- `tool.exposure_resolved`
- `tool.denied`
- `tool.started`
- `tool.completed`
- `tool.outcome_unknown`
- `ExecutionPolicy.Decision` / denial reason code 추가
- policy 재검사 시 `POLICY_CHANGED`, inactive Tool은 `TOOL_INACTIVE`로 구분

### Phase L4 — Scheduling

- `schedule.created`
- `schedule.run_started`
- `schedule.run_completed`
- `schedule.cancelled`
- `schedule.aborted`
- scheduled run에 `scheduleId / runIndex / toolCallId / actionId` correlation 연결
- actor invalidation / scheduling disable / Tool inactive / execution policy revoke / authority revoke / audit failure / timeout / outcome unknown / server stopping abort reason 기록

Operational logging delegate 예외는 runtime 또는 Tool 실행 의미를 바꾸지 않도록 fail-open 처리했다. 기존 JSONL Audit 및 mutation fail-closed semantics는 변경하지 않았다.

## 수행한 확인

사용자 요청에 따라 build 확인만 수행했다.

- GitHub Actions run: `36315364238`
- 검증 commit: `7d7fa10b27a00c17905e501d21cb51e2a0369883`
- 명령: `./gradlew build --stacktrace`
- 결과: **PASS**

## 수행하지 않은 확인

- clean-server boot smoke
- Paper/Fabric/NeoForge 수동 시나리오
- live Jev/OpenAI provider 호출
- 별도 operational logging acceptance 시나리오
