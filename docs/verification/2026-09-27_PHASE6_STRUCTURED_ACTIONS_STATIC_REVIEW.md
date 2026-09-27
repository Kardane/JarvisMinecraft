# Phase 6 structured actions static review

작성일: 2026-09-27  
대상 브랜치: `codex/phase1-config-foundation`

## 추가 Tool

### weather_set

```json
{
  "worldId": "world",
  "weather": "RAIN",
  "durationSeconds": 600
}
```

- stateChanging=true
- Risk.LOW
- weather: CLEAR / RAIN / THUNDER
- durationSeconds: 1~3600
- loaded world only
- actionId required

### time_set

```json
{
  "worldId": "world",
  "timeOfDay": 6000
}
```

- stateChanging=true
- Risk.LOW
- timeOfDay: 0~23999
- loaded world only
- current day count preserved
- actionId required

## 실행 경계

두 Tool은 다음 경로를 모두 통과해야 한다.

```text
ToolRegistry active
→ ExecutionPolicy allowlist
→ Jev ACTION_REQUEST route
→ Luna strict function schema
→ ToolArgumentCodec strict parse
→ state-changing pre-audit
→ ExecutionPolicy 재검사
→ CommonRuntime current online OP 재검사
→ platform server-thread API
```

기본 `READ_TALK`에서는 state-changing Tool이 노출되지 않는다.
`EXECUTE_LITE`에서도 exact allowlist가 없으면 노출되지 않는다.

## 플랫폼 구현

- Paper: Bukkit `World` weather/time API
- Fabric: `ServerWorld#setWeather`, `setTimeOfDay`
- NeoForge: `ServerLevel#setWeatherParameters`, `setDayTime`

raw console command dispatch는 추가하지 않았다.

입력 `durationSeconds`는 platform 내부 weather tick 단위로 변환한다.
`time_set`은 현재 absolute day 값을 읽어 day base를 보존한 뒤 requested
time-of-day를 적용한다.

## Contract 동기화

같이 변경한 자산:

- `Protocol.ToolName`
- `ToolModels`
- `ToolArgumentCodec`
- `LunaToolSchemas`
- `AuditArgumentSummaries`
- `StandardMinecraftTools`
- `StandardMinecraftToolService`
- `StandardPlatformAccess`
- `protocol/schema/protocol.schema.json`
- protocol valid/invalid fixtures
- fixture manifest
- `docs/tools.md`

schema에서 `teleport_staff / weather_set / time_set`은 모두 state-changing으로
분류되어 non-null actionId가 필요하다.

## 검증 상태

사용자 요청에 따라 빌드와 테스트는 실행하지 않았다.

미검증:

- Java/Gradle compile
- protocol fixture validator
- Paper weather/time runtime
- Fabric 1.21.8 weather/time runtime
- NeoForge 1.21.8 weather/time runtime
- EXECUTE_LITE allowlist integration
- audit + OUTCOME_UNKNOWN 회귀
- clean-server boot

이 문서는 정적 검토 상태만 기록한다.
