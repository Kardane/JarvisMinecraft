# JARVIS testing guide

갱신일: 2026-09-27

이 문서는 현재 Embedded Brain 기반 `main` 구조의 테스트 기준만 다룬다. 과거 Phase/E-series 검증 기록은 유지하지 않는다.

## 1. 기본 빌드

Java 21에서 실행한다.

```bash
./gradlew build --stacktrace
```

이 명령은 common/platform verification과 artifact 검사를 포함하는 기본 deterministic gate다.

플랫폼별 배포 artifact:

```text
minecraft/paper/build/libs/jarvisminecraft-paper.jar
minecraft/fabric/build/libs/jarvisminecraft-fabric.jar
minecraft/neoforge/build/libs/jarvisminecraft-neoforge.jar
```

## 2. 주요 deterministic verification

필요할 때 개별 실행:

```bash
./gradlew :minecraft:common:jarvisConfigVerification --stacktrace
./gradlew :minecraft:common:embeddedBrainVerification --stacktrace
./gradlew :minecraft:common:embeddedBrainParityVerification --stacktrace

./gradlew :minecraft:paper:t06Verification --stacktrace
./gradlew :minecraft:fabric:t07Verification --stacktrace
./gradlew :minecraft:neoforge:t08Verification --stacktrace

./gradlew verifyE16Artifacts --stacktrace
```

Paper optional provider verification:

```bash
./gradlew \
  :minecraft:paper:t11Verification \
  :minecraft:paper:t12Verification \
  :minecraft:paper:t13Verification \
  :minecraft:paper:t14Verification \
  --stacktrace
```

## 3. Clean-server boot

```bash
./gradlew :minecraft:paper:jar --stacktrace
bash scripts/e16-boot-smoke.sh paper

./gradlew :minecraft:fabric:remapJar --stacktrace
bash scripts/e16-boot-smoke.sh fabric

./gradlew :minecraft:neoforge:jar --stacktrace
bash scripts/e16-boot-smoke.sh neoforge
```

NeoForge 전용 smoke:

```bash
./gradlew :minecraft:neoforge:verifyT08BootSmoke --stacktrace
```

## 4. Live AI verification

실제 provider 호출은 기본 CI에 포함하지 않는다. API 비용이 발생할 수 있다.

```bash
OPENAI_API_KEY=... TYPESAFE_API_KEY=... \
  ./gradlew :minecraft:common:embeddedBrainLiveVerification --stacktrace
```

확인 대상:

- Jev engagement/route/reasoning parse
- deterministic fallback
- Luna Tool loop
- reasoning effort 유지
- Tool result 이후 후속 model round
- provider timeout/error safe failure

## 5. 수동 게임플레이 smoke

Paper/Fabric/NeoForge 각각 별도 서버 디렉터리를 사용한다. 서버 바이너리, world, log, secret 파일은 repository에 commit하지 않는다.

권장 사용자:

```text
Admin = online OP
Guest = non-OP
Other = optional observer
```

핵심 시나리오:

- wake word DIRECT 요청과 FOLLOW_UP
- `/jm status` 출력
- READ_TALK에서 mutation Tool 미노출
- EXECUTE_LITE에서 allowlisted `teleport_staff/weather_set/time_set`
- audience=ALL인 non-OP의 대화 가능 + Minecraft Tool 0개
- de-op/logout/session-end race에서 Tool/reply 차단
- Jev failure의 read-only fallback
- Luna failure safe response
- Audit 장애 시 mutation fail-closed
- state-changing timeout 시 `OUTCOME_UNKNOWN`, 자동 재시도 없음
- ACTIVE proactive의 cooldown/in-flight 제한
- ACTIVE proactive에서 mutation Tool hard-block
- scheduled action의 policy/authority 재검사
- shutdown 시 pending schedule 취소

## 6. Scheduling E2E

예시 설정:

```yaml
execution:
  mode: EXECUTE_LITE
  actors: OP
  lite:
    allow-tools:
      - teleport_staff
      - weather_set
      - time_set

scheduling:
  enabled: true
  max-delay-seconds: 60
  max-duration-seconds: 60
```

확인:

- one-shot delay
- repeating interval/duration
- overlap 없음
- owner만 cancel 가능
- 실행 전 scheduling disable / policy revoke / de-op / logout 차단
- ERROR/TIMEOUT/OUTCOME_UNKNOWN 이후 반복 중단
- restart 후 schedule 복구되지 않음

## 7. ACTIVE E2E

안전한 초기값은 `READ_TALK` + scheduling disabled다.

확인:

- 일반 공개 채팅은 원래대로 표시
- Jev `IGNORE` 시 무응답
- `START_CONVERSATION` + confidence threshold 통과 시 세션 생성
- 1초 classification interval
- proactive classifier 동시 1개
- cooldown 적용
- 분류 중 PASSIVE 전환/audience revoke/direct-session 생성 시 activation drop
- proactive request에는 mutation/scheduling control Tool 없음

## 8. Audit/operational logging 확인

Audit:

- mutation `PRE_EXECUTION` 존재
- post result 존재
- `requestId/toolCallId/actionId` correlation
- scheduled run마다 새 `toolCallId/actionId`
- provider key/Authorization/raw prompt/raw chat 없음

Operational log:

- request/Jev/Luna/Tool/schedule/proactive lifecycle 확인
- Audit health 변화 시 `audit.degraded/unhealthy/recovered`
- 같은 Audit health 상태가 매 poll마다 반복 출력되지 않음
- `/jm status`에 runtime, interaction, execution, scheduling, AI queue, proactive in-flight, Audit health가 표시됨

## 9. Release gate

릴리스 전 최소 기준:

- `./gradlew build` PASS
- 3개 플랫폼 clean boot PASS
- Paper/Fabric/NeoForge 핵심 gameplay smoke PASS
- non-OP mutation 차단 PASS
- proactive mutation 차단 PASS
- scheduling reauthorization PASS
- Audit secret leakage 없음
- shutdown/restart lifecycle 이상 없음
- credentials가 있는 릴리스 검증에서는 live Jev/Luna PASS
