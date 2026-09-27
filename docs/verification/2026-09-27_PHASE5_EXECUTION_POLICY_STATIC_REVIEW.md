# Phase 5 execution policy static review

작성일: 2026-09-27  
대상 브랜치: `codex/phase1-config-foundation`

## 구현 범위

Phase 5에서 `jarvis.execution.*`을 실제 Tool exposure 경로에 연결했다.

```text
READ_TALK
  -> active read-only Tools

EXECUTE_LITE
  -> active read-only Tools
  -> + explicit lite.allow-tools
  -> state-changing + Risk.LOW only

EXECUTE
  -> active read-only Tools
  -> + explicit full.allow-tools
  -> registered state-changing Tools only
```

선택된 mode의 `deny-tools`가 항상 우선한다.

## Strict config validation

execution allow/deny 값은 exact Tool wire name이어야 한다.

현재 state-changing catalog:

```text
teleport_staff
  stateChanging=true
  risk=LOW
```

validation 규칙:

- unknown Tool name -> config error
- allow-tools에 read-only Tool -> config error
- lite.allow-tools에 LOW 이외 risk -> config error
- deny-tools에는 known read-only/state-changing Tool 모두 허용
- allow와 deny에 동시에 있으면 deny 우선

wildcard는 지원하지 않는다.

## 권한 경계

interaction audience와 execution authority는 계속 분리된다.

```text
non-OP accepted by ALL/WHITELIST
  -> conversation 가능
  -> requesterToolAuthority=false
  -> Minecraft Tool set = empty
```

OP라도 `READ_TALK`이면 state-changing Tool은 노출되지 않는다.
`CommonRuntime`의 current-online-OP 재검증도 유지한다.

## In-flight 정책 변화

초기 planning에서 `ExecutionPolicy`를 적용한 뒤 route를 계산하므로 request가
시작된 이후 config가 완화되더라도 기존 request는 새 mutation Tool을 얻지
못한다.

정책 강화는 각 Luna round, Tool 처리 시작, state-changing pre-audit 성공 후
CommonRuntime handoff 직전에 다시 확인한다.

## Proactive 준비

interaction origin이 `PROACTIVE` 또는 `PROACTIVE_CANDIDATE`이면
state-changing Tool을 강제로 제거한다. 실제 ACTIVE proactive 진입은 아직
구현하지 않았다.

## 범위 밖

이번 Phase에서는 새로운 state-changing Tool, scheduling, admin command,
non-OP execution actor를 추가하지 않았다.

## 검증 상태

사용자 요청에 따라 테스트/빌드는 실행하지 않았다.

미검증:
- Java/Gradle compile
- READ_TALK route regression
- EXECUTE_LITE + teleport_staff
- deny-tools precedence
- invalid config startup failure
- config tightening during in-flight request
- Paper/Fabric/NeoForge clean boot
- 기존 E8-E16 회귀

이 문서는 정적 코드 검토 상태만 기록한다.
