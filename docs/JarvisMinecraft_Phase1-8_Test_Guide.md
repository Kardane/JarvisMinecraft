# JarvisMinecraft Phase 1~8 테스트 실행 가이드

작성일: 2026-09-27
대상 브랜치: `codex/phase1-config-foundation`
대상 상태: Phase 1~8 구현 후 최초 통합 검증
기준 Java: Java 21
기준 Minecraft: 1.21.8
플랫폼: Paper / Fabric / NeoForge

---

## 0. 목적

이 문서는 Phase 1~8에서 추가된 기능을 실제로 검증하기 위한 **권장 실행 순서**다.

검증 순서는 다음 원칙을 따른다.

1. 외부 API를 호출하지 않는 검증부터 시작한다.
2. compile/build 실패를 먼저 제거한다.
3. protocol/config/정책 테스트를 통과시킨다.
4. 공통 Brain 로직을 fake runtime으로 검증한다.
5. 플랫폼별 adapter/API 동작을 검증한다.
6. clean-server boot를 검증한다.
7. 마지막에만 실제 Jev/OpenAI API를 호출한다.
8. LIVE AI 테스트가 통과한 뒤 실제 Minecraft 플레이 시나리오를 수행한다.
9. ACTIVE와 scheduling처럼 race가 있는 기능은 맨 마지막에 집중 검증한다.

**중요:** Phase 1~8 작업 중 빌드/테스트를 실행하지 않았으므로, 아래 순서를 처음부터 끝까지 수행해야 한다.

---

# 1. 전체 테스트 순서

```text
0. 환경 준비
↓
1. Git/branch 상태 확인
↓
2. Java/Gradle wrapper 확인
↓
3. compile-only 검증
↓
4. 기존 deterministic verification
↓
5. 전체 ./gradlew build
↓
6. artifact 검사
↓
7. protocol/schema/fixture 검증
↓
8. Phase 1~8 신규 단위/정책 테스트 작성 및 실행
↓
9. 플랫폼별 fake-adapter verification
↓
10. clean-server boot
↓
11. Paper 수동 gameplay smoke
↓
12. Fabric 수동 gameplay smoke
↓
13. NeoForge 수동 gameplay smoke
↓
14. Jev live verification
↓
15. GPT-6 Luna live verification
↓
16. Phase 7 scheduling E2E
↓
17. Phase 8 ACTIVE proactive E2E
↓
18. 권한/실패/race 회귀
↓
19. 최종 release gate
```

앞 단계가 실패하면 뒤 단계로 진행하지 않는 것이 좋다.

---

# 2. 환경 준비

## 2.1 저장소 받기

```bash
git clone https://github.com/Kardane/JarvisMinecraft.git
cd JarvisMinecraft
git fetch origin
git checkout codex/phase1-config-foundation
git pull --ff-only
```

이미 clone되어 있다면:

```bash
git fetch origin
git checkout codex/phase1-config-foundation
git status
```

기대 결과:

```text
On branch codex/phase1-config-foundation
nothing to commit, working tree clean
```

---

## 2.2 Java 확인

```bash
java -version
```

기대:

```text
Java 21
```

---

## 2.3 Gradle Wrapper 확인

Linux/macOS:

```bash
chmod +x gradlew
./gradlew --version
```

Windows PowerShell:

```powershell
.\gradlew.bat --version
```

확인:

- Gradle Wrapper가 정상 시작되는가
- JVM이 Java 21인가
- dependency resolution 오류가 없는가

---

# 3. 1단계 — Compile 검증

## common

```bash
./gradlew :minecraft:common:compileJava --stacktrace
```

## Paper

```bash
./gradlew :minecraft:paper:compileJava --stacktrace
```

## Fabric

```bash
./gradlew :minecraft:fabric:compileJava --stacktrace
```

## NeoForge

```bash
./gradlew :minecraft:neoforge:compileJava --stacktrace
```

### 특히 확인할 Phase 4~8 위험 지점

Phase 4:
- `StyledChatMessage`
- Paper Adventure API
- Fabric Text/Style API
- NeoForge Component/Style API
- sound API signature

Phase 6:
- Paper weather/time API
- Fabric `ServerWorld#setWeather`
- Fabric `ServerWorld#setTimeOfDay`
- NeoForge `ServerLevel#setWeatherParameters`
- NeoForge `ServerLevel#setDayTime`

Phase 7:
- `ScheduledActionService`
- `SchedulingPolicy`
- 새 `ToolName` enum에 따른 exhaustive switch
- `schedule_action`
- `cancel_scheduled_action`

Phase 8:
- `AmbientConversationTracker`
- `BrainGateway.considerProactive`
- `EmbeddedBrain.classifyProactive`
- Paper/Fabric/NeoForge chat hook

### PASS 조건

4개 compile task가 모두 성공해야 한다.

---

# 4. 2단계 — 기존 deterministic verification

## Phase 1 config

```bash
./gradlew :minecraft:common:jarvisConfigVerification --stacktrace
```

검증 대상:
- `JarvisConfig`
- 기본값
- invalid config rejection
- fail-safe reload
- scheduling 범위 validation

## Embedded Brain

```bash
./gradlew :minecraft:common:embeddedBrainVerification --stacktrace
```

기존 검증:
- CommonRuntime direct invocation
- read-only Tool loop
- pre-audit fail closed
- reply 전 authority 재확인
- session cancellation
- stop 이후 late delivery 억제
- JSONL audit
- Embedded settings contract

## Policy parity

```bash
./gradlew :minecraft:common:embeddedBrainParityVerification --stacktrace
```

## Paper adapter

```bash
./gradlew :minecraft:paper:t06Verification --stacktrace
```

## Fabric adapter

```bash
./gradlew :minecraft:fabric:t07Verification --stacktrace
./gradlew :minecraft:fabric:verifyNoClientImports --stacktrace
```

## NeoForge adapter

```bash
./gradlew :minecraft:neoforge:t08Verification --stacktrace
./gradlew :minecraft:neoforge:verifyNoClientImports --stacktrace
```

## Paper optional providers

```bash
./gradlew \
  :minecraft:paper:t11Verification \
  :minecraft:paper:t12Verification \
  :minecraft:paper:t13Verification \
  :minecraft:paper:t14Verification \
  --stacktrace
```

---

# 5. 3단계 — 전체 Build

앞 단계가 모두 통과한 후:

```bash
./gradlew clean build --stacktrace
```

현재 CI의 핵심 Java/Gradle gate다.

### PASS 조건

```text
BUILD SUCCESSFUL
```

뿐 아니라 verification task가 예상대로 실행됐는지 로그도 확인한다.

---

# 6. 4단계 — Artifact 검사

빌드 결과:

```text
minecraft/paper/build/libs/jarvisminecraft-paper.jar
minecraft/fabric/build/libs/jarvisminecraft-fabric.jar
minecraft/neoforge/build/libs/jarvisminecraft-neoforge.jar
```

자동 검사:

```bash
./gradlew verifyE16Artifacts --stacktrace
```

확인:
- platform entrypoint 포함
- Embedded Brain 포함
- Jev/OpenAI runtime 포함
- relocated OpenAI/Jackson/OkHttp 포함
- unrelocated dependency 없음
- Gson 중복 bundling 없음
- Node/JS runtime asset 없음

---

# 7. 5단계 — Protocol / Fixture 검증

Phase 6~7에서 protocol schema/fixture가 바뀌었다.

valid fixture:
- `tool-request-weather-set.json`
- `tool-result-weather-set.json`
- `tool-request-time-set.json`
- `tool-result-time-set.json`
- `tool-request-schedule-weather.json`
- `tool-result-schedule-weather.json`
- `tool-request-cancel-schedule.json`
- `tool-result-cancel-schedule.json`

invalid fixture:
- `weather-set-missing-action-id.json`
- `time-set-out-of-range.json`
- `schedule-missing-action-id.json`
- `schedule-duration-too-large.json`

현재 Node protocol runner는 Embedded 전환 과정에서 제거되었으므로, **신규 Java verification task를 추가하는 것을 권장**한다.

권장 task:

```text
:minecraft:common:protocolFixtureVerification
```

반드시 검사할 것:

```text
valid fixture   -> 모두 PASS
invalid fixture -> 모두 FAIL
```

state-changing/control Tool:

```text
teleport_staff
weather_set
time_set
schedule_action
cancel_scheduled_action
```

은 모두 non-null `actionId`가 필요하다.

---

# 8. 6단계 — Phase 1 Config 신규 테스트

## 기본값

기대:

```text
interaction.mode = PASSIVE
audience = OP
reasoning.mode = AUTO
execution.mode = READ_TALK
scheduling.enabled = false
```

## invalid model

`model.name`을 다른 값으로 설정.

기대: validation FAIL.

## unknown Tool

```yaml
allow-tools:
  - does_not_exist
```

기대: FAIL.

## read-only Tool을 allowlist에 등록

```yaml
lite:
  allow-tools:
    - get_server_status
```

기대: FAIL.

## scheduling control Tool을 execution allowlist에 등록

```yaml
lite:
  allow-tools:
    - schedule_action
```

기대: FAIL.

## scheduling range

```text
0  -> FAIL
60 -> PASS
61 -> FAIL
```

`max-delay-seconds`, `max-duration-seconds` 모두 검사한다.

## fail-safe reload

1. valid config A
2. invalid config B reload
3. current snapshot 확인

기대: reload 실패 후 A 유지.

---

# 9. 7단계 — Phase 2 Interaction 테스트

## Wake word

```text
자비스 서버 상태     -> DIRECT
재비스 서버 상태     -> DIRECT
JarVis 서버 상태    -> DIRECT
자비스팅 서버 상태   -> PUBLIC_CHAT
중간에 자비스 등장   -> PUBLIC_CHAT
```

## Follow-up

wake word로 session 생성 후 호출어 없는 메시지.

기대: `FOLLOW_UP`, session ID 유지.

## TTL

짧은 TTL 설정 후:
- TTL 이전 follow-up -> 전달
- TTL 이후 -> 일반 채팅

## 종료

```text
대화 끝
```

기대:
- Brain 호출 없음
- session 종료

## Escape

```text
!이건 일반 채팅
```

기대: Brain 우회.

## Audience

각각:
- OP
- WHITELIST
- ALL
- BLACKLIST

특히 `ALL + non-OP`에서:
- 일반 Luna 대화 가능
- Minecraft Tool 0개

---

# 10. 8단계 — Phase 3 Jev / Reasoning 테스트

## Fixed reasoning

`NONE / LOW / MEDIUM / HIGH` 각각 강제하고 Jev 결과보다 config가 우선하는지 확인.

## AUTO

Jev fixture가 `HIGH`를 반환하면 LunaTurnInput도 HIGH인지 확인.

Tool result 후 두 번째 Luna round에서도 동일 effort 유지.

## Jev failure fallback

```yaml
reasoning:
  mode: AUTO
  fallback: MEDIUM
```

Jev timeout/error 시 MEDIUM으로 내려가며 mutation 권한은 확대되지 않아야 한다.

## Engagement

DIRECT/FOLLOW_UP은 RESPOND.

PROACTIVE_CANDIDATE는 START_CONVERSATION 또는 IGNORE.

---

# 11. 9단계 — Phase 4 Response UX 테스트

## Prefix

```yaml
prefix: "&5[JARVIS]&r "
```

기대: prefix만 스타일 적용.

## Model body injection 방지

fake Luna reply:

```text
&c빨간색이어야 한다
```

기대: `&c`가 formatting 명령으로 해석되지 않음.

## Progress threshold

500ms 응답:
- progress 없음
- final만

2500ms 응답:
- progress 1회
- final 1회

## Late-progress race

100~1000회 반복 권장.

PASS:

```text
final 뒤 progress = 0
```

## Sound

- requester만 들음
- 다른 플레이어는 안 들음
- progress에는 sound 없음
- invalid sound ID여도 chat response는 성공

---

# 12. 10단계 — Phase 5 ExecutionPolicy 테스트

## READ_TALK

등록된 state-changing Tool이 Luna에 노출되지 않아야 한다.

## EXECUTE_LITE

```yaml
lite:
  allow-tools:
    - teleport_staff
    - weather_set
    - time_set
```

세 LOW-risk mutation만 추가 노출.

## allow + deny 충돌

deny가 우선.

## read-only deny

`get_server_status`를 deny하면 read Tool도 숨겨져야 한다.

## non-OP

audience=ALL이어도 Minecraft Tool 0개.

## in-flight tightening

request 중 EXECUTE_LITE -> READ_TALK.

기대: 실행 직전 차단.

## in-flight relaxation

READ_TALK으로 시작한 요청을 중간에 EXECUTE로 완화.

기대: 기존 request가 새 mutation Tool을 얻지 못함.

---

# 13. 11단계 — Phase 6 Structured Action 테스트

Paper/Fabric/NeoForge 각각 수행한다.

## weather_set

### CLEAR / RAIN / THUNDER

각각 30초로 테스트.

확인:
- 요청한 world만 변경
- 다른 world 영향 없음

## duration range

```text
0    -> reject
1    -> accept
3600 -> accept
3601 -> reject
```

## unknown world

기대: `NOT_FOUND`, 자동 world load 없음.

## time_set

현재 full time의 day base를 유지하면서 time-of-day만 변경되는지 확인.

## time range

```text
-1    -> reject
0     -> accept
23999 -> accept
24000 -> reject
```

## de-op race

planning 후 실행 직전 OP 제거.

기대: world state unchanged.

---

# 14. 12단계 — Phase 7 Scheduling 단위 테스트

`ScheduledActionServiceVerificationMain` 같은 별도 deterministic verification을 권장한다.

실제 sleep보다 fake clock + controllable scheduler가 좋다.

## one-shot

```text
delay=10
interval=null
duration=null
```

기대:
- 즉시 scheduleId
- 10초 전 실행 0
- 10초 후 실행 1
- 이후 없음

## repeating

```text
delay=1
interval=2
duration=6
```

기대:
- 직렬 실행
- 만료 후 종료

## overlap 방지

interval 1초, Tool 실행 2초.

기대: 동시 실행 2개가 없어야 한다.

## cancel

예약 직후 cancel.

기대: 실행 0.

## 다른 사용자 cancel

A의 scheduleId를 B가 취소.

기대: 취소 실패, A schedule 유지.

## scheduling disable during delay

등록 후 실행 전 `enabled=false`.

기대: 실행 안 됨.

## ExecutionPolicy revoke during delay

등록 후 nested Tool deny.

기대: 실행 안 됨.

## de-op during delay

기대: 실행 시점 CommonRuntime에서 차단.

## logout

기대: actor schedule 취소.

## Brain shutdown

기대: 전체 pending schedule 취소, scheduler 종료.

## 실패 후 repeat

ERROR/TIMEOUT/OUTCOME_UNKNOWN 발생 시 다음 반복 없어야 한다.

---

# 15. 13단계 — Phase 8 ACTIVE 단위 테스트

fake Jev + fake Luna + fake platform으로 먼저 한다.

## PASSIVE

일반 공개 채팅 -> proactive classify 0회.

## ACTIVE + IGNORE

원 공개 채팅은 유지되며 session/Luna 호출 없음.

## ACTIVE + START_CONVERSATION

confidence 0.90, threshold 0.75.

기대:
- session 생성
- Luna origin=`PROACTIVE`
- public reply
- 이후 일반 FOLLOW_UP

## confidence boundary

```text
0.74 -> no response
0.75 -> response
0.76 -> response
```

## one in-flight

첫 Jev future를 block한 상태에서 채팅 여러 개.

기대: 동시 proactive Jev 1개.

## 1초 classification interval

1초 안의 다수 메시지에 classification 남발 없음.

## cooldown

proactive 성공 후 15초 내 재개입 없음.

## ACTIVE -> PASSIVE race

Jev 완료 전에 PASSIVE로 변경.

기대: activation 단계에서 drop.

## audience revoke race

분류 중 blacklist.

기대: session 생성 없음.

## direct-session race

proactive 분류 중 사용자가 직접 wake word.

기대: proactive duplicate session 없음.

## proactive Tool safety

OP + EXECUTE에서도 아래는 없어야 한다.

```text
teleport_staff
weather_set
time_set
schedule_action
cancel_scheduled_action
```

비OP는 Minecraft Tool 0개.

## proactive progress

느린 Luna여도 waiting/progress 메시지 없음.

---

# 16. 14단계 — Clean-server Boot

## Paper

```bash
./gradlew :minecraft:paper:jar --stacktrace
bash scripts/e16-boot-smoke.sh paper
```

## Fabric

```bash
./gradlew :minecraft:fabric:remapJar --stacktrace
bash scripts/e16-boot-smoke.sh fabric
```

## NeoForge

```bash
./gradlew :minecraft:neoforge:jar --stacktrace
bash scripts/e16-boot-smoke.sh neoforge
```

NeoForge 기존 task:

```bash
./gradlew :minecraft:neoforge:verifyT08BootSmoke --stacktrace
```

확인:
- NoClassDefFoundError 없음
- NoSuchMethodError 없음
- config parse 정상
- entrypoint 정상
- shutdown 정상
- scheduler thread leak 없음

---

# 17. 15단계 — 실제 서버 수동 테스트 준비

플랫폼별 별도 서버 권장:

```text
test-servers/
  paper/
  fabric/
  neoforge/
```

플레이어 최소 2명:

```text
Admin = OP
Guest = non-OP
```

가능하면 3명:
- Admin
- Guest
- Other

---

# 18. 16단계 — Paper Gameplay Smoke

Paper에서 먼저 전체 기능을 검증한다.

## 기본 PASSIVE

```yaml
interaction:
  mode: PASSIVE

execution:
  mode: READ_TALK

scheduling:
  enabled: false
```

테스트:
- 일반 채팅 -> 그대로
- `자비스 안녕` -> reply
- 호출어 없는 후속 질문 -> FOLLOW_UP

## Prefix / Sound

- 색 prefix 확인
- requester-only sound 확인

## READ_TALK

텔레포트 요청 -> mutation 실행 안 됨.

## EXECUTE_LITE

```yaml
execution:
  mode: EXECUTE_LITE
  actors: OP
  lite:
    allow-tools:
      - teleport_staff
      - weather_set
      - time_set
```

- self teleport
- weather
- time

검증.

## non-OP audience

audience=ALL.

Guest:
- 일반 JARVIS 대화 가능
- mutation 불가

---

# 19. 17단계 — Fabric Gameplay Smoke

Paper와 같은 시나리오를 반복한다.

중점:
- dedicated-server API
- chat callback thread
- styled text
- sound registry
- weather/time API

로그에 없어야 하는 것:

```text
off-thread warning
client class load error
NoSuchMethodError
NoClassDefFoundError
```

---

# 20. 18단계 — NeoForge Gameplay Smoke

Fabric과 동일한 시나리오.

중점:
- `ServerChatEvent`
- `ServerLevel#setWeatherParameters`
- `setDayTime`
- styled message
- sound registry
- scheduler shutdown

---

# 21. 19단계 — Live Jev / GPT-6 Luna

**여기부터 실제 API 비용이 발생할 수 있다.**

Linux/macOS:

```bash
export OPENAI_API_KEY="..."
export TYPESAFE_API_KEY="..."

./gradlew :minecraft:common:embeddedBrainLiveVerification --stacktrace
```

Windows PowerShell:

```powershell
$env:OPENAI_API_KEY="..."
$env:TYPESAFE_API_KEY="..."

.\gradlew.bat :minecraft:common:embeddedBrainLiveVerification --stacktrace
```

검증:
- Jev HTTP
- multi-question shape
- route/engagement/reasoning parse
- Luna `gpt-6-luna`
- dynamic reasoning effort
- function schema
- Tool result 후 second round

---

# 22. 20단계 — Phase 7 실제 Scheduling E2E

설정:

```yaml
execution:
  mode: EXECUTE_LITE
  actors: OP
  lite:
    allow-tools:
      - weather_set
      - time_set
      - teleport_staff

scheduling:
  enabled: true
  max-delay-seconds: 60
  max-duration-seconds: 60
```

## 10초 후 날씨

```text
자비스 10초 후 이 월드에 비 오게 해줘
```

확인:
- 즉시 schedule 등록
- 10초 전 변경 없음
- 10초 후 변경

## 반복

예:

```text
자비스 2초 후부터 5초마다 20초 동안 시간을 낮으로 설정해줘
```

확인:
- 중첩 실행 없음
- duration 이후 종료
- 각 실행 audit 기록

## cancel

scheduleId 기반 취소.

## de-op

예약 후 de-op -> 실행 차단.

## logout

예약 후 logout -> actor pending schedule 취소.

## restart

예약 후 restart -> 복구되지 않는 것이 현재 정상.

---

# 23. 21단계 — Phase 8 ACTIVE 실제 E2E

안전한 초기 설정:

```yaml
interaction:
  mode: ACTIVE
  audience:
    mode: OP

execution:
  mode: READ_TALK

scheduling:
  enabled: false

proactive:
  context-messages: 12
  cooldown-seconds: 15
  confidence-threshold: 0.75
```

## 잡담

여러 일반 메시지.

기대: JARVIS가 과도하게 끼어들지 않음.

## 개입 가치가 있는 질문

Jev가 START_CONVERSATION을 선택하면:
- 원 메시지 정상 표시
- JARVIS 별도 reply
- session 생성
- 후속 메시지 가능

## cooldown

15초 내 재개입 없음.

## proactive mutation 차단

OP + EXECUTE 상태에서도 proactive에서는 mutation이 없어야 한다.

## proactive read-only

OP의 경우 read-only Tool은 사용할 수 있다.

## non-OP ACTIVE

audience=ALL.

Guest:
- proactive 일반 대화 가능
- Minecraft Tool 없음

---

# 24. 22단계 — Race 집중 테스트

## progress vs final

100~1000회 반복.

PASS:

```text
final 뒤 progress = 0
```

## proactive Jev vs direct session

PASS:
- session 1개
- duplicate reply 없음

## proactive audience revoke

PASS: unsolicited reply 없음.

## schedule cancel vs trigger

허용 결과:

```text
cancel 성공 -> 실행 없음
이미 실행 시작 -> cancel 실패
```

이중 실행은 금지.

## policy tightening vs Tool execution

pre-audit 중 정책 강화.

PASS: CommonRuntime handoff 전 차단.

## logout vs final reply

PASS: late public reply 없음.

---

# 25. 23단계 — Audit 검사

Paper:

```text
plugins/JarvisMinecraft/audit/
```

Fabric/NeoForge:

```text
config/jarvisminecraft/audit/
```

확인:
- mutation PRE_EXECUTION 존재
- 실행 결과 audit 존재
- actionId 존재
- scheduled run마다 새 actionId
- API key 없음
- Authorization header 없음
- secret 원문 없음
- OUTCOME_UNKNOWN 자동 retry 없음

---

# 26. 24단계 — 성능/부하

ACTIVE가 핵심 대상이다.

예:

```text
10 players
각 1 msg/sec
60 sec
```

확인:
- tick stall 없음
- proactive Jev 동시 요청 1개
- ambient retention 최대 50
- queue 폭증 없음
- heap이 지속 증가하지 않음

측정 권장:
- TPS
- MSPT p50/p95
- CPU
- heap
- outbound Jev request rate

과거 T10/A11 결과는 현재 Phase 1~8의 성능 증거로 재사용하지 않는다.

---

# 27. 25단계 — 보안/권한 체크리스트

- [ ] non-OP는 mutation Tool을 받지 않는다.
- [ ] audience=ALL이 execution 권한을 주지 않는다.
- [ ] de-op 직후 mutation이 차단된다.
- [ ] logout 후 stale reply가 없다.
- [ ] READ_TALK에서 mutation schema가 없다.
- [ ] deny-tools가 allow-tools보다 우선한다.
- [ ] ACTIVE proactive에서 mutation Tool이 없다.
- [ ] scheduled run마다 권한을 다시 검사한다.
- [ ] scheduled run마다 policy를 다시 검사한다.
- [ ] OUTCOME_UNKNOWN을 자동 retry하지 않는다.
- [ ] raw console command 경로가 없다.
- [ ] arbitrary filesystem access가 없다.
- [ ] SQL/code execution 경로가 없다.
- [ ] API key가 audit/chat/log에 노출되지 않는다.
- [ ] invalid model output이 권한으로 해석되지 않는다.

---

# 28. 26단계 — 최종 Regression

```bash
./gradlew clean build --stacktrace
./gradlew verifyE16Artifacts --stacktrace
```

live key가 있을 때:

```bash
OPENAI_API_KEY=... TYPESAFE_API_KEY=... \
  ./gradlew :minecraft:common:embeddedBrainLiveVerification --stacktrace
```

이후 3개 플랫폼 clean boot와 핵심 gameplay smoke를 다시 수행한다.

---

# 29. 권장 신규 자동화 Task

Phase 1~8 유지보수를 위해 다음 task 추가를 권장한다.

```text
:minecraft:common:interactionPolicyVerification
:minecraft:common:reasoningPolicyVerification
:minecraft:common:responseUxVerification
:minecraft:common:executionPolicyVerification
:minecraft:common:structuredActionVerification
:minecraft:common:scheduledActionVerification
:minecraft:common:activeProactiveVerification
:minecraft:common:protocolFixtureVerification
```

최종적으로 root `check`에 묶어:

```bash
./gradlew check
```

하나로 대부분의 deterministic regression을 잡는 상태가 이상적이다.

---

# 30. 테스트 우선순위

## P0 — 반드시

1. 4개 모듈 compile
2. `./gradlew clean build`
3. config verification
4. Embedded Brain verification
5. ExecutionPolicy
6. weather/time
7. scheduling 권한 재검증
8. ACTIVE mutation hard-block
9. 3개 플랫폼 clean boot
10. OP/non-OP authority smoke

## P1 — release 전 강력 권장

1. progress/final race
2. scheduling cancel race
3. policy tightening race
4. ACTIVE cooldown/in-flight throttle
5. prefix/sound
6. Jev live
7. Luna live
8. 실제 scheduling gameplay

## P2 — 장기 안정성

1. 고부하 ACTIVE chat
2. 장시간 memory observation
3. optional Provider 실제 runtime smoke
4. performance baseline 비교
5. repeated restart/stop lifecycle

---

# 31. 결과 기록 형식

권장 파일:

```text
docs/verification/
  YYYY-MM-DD_PHASE1_8_DETERMINISTIC_TEST.md
  YYYY-MM-DD_PHASE1_8_PAPER_LIVE.md
  YYYY-MM-DD_PHASE1_8_FABRIC_LIVE.md
  YYYY-MM-DD_PHASE1_8_NEOFORGE_LIVE.md
  YYYY-MM-DD_PHASE1_8_AI_LIVE.md
  YYYY-MM-DD_PHASE1_8_RELEASE_GATE.md
```

각 문서 기록 항목:

```text
commit SHA
Java version
OS
Minecraft version
Paper/Fabric/NeoForge version
config snapshot (secret 제외)
실행 명령
PASS/FAIL
실패 로그
재현 방법
수정 commit
재검증 결과
```

secret은 절대 기록하지 않는다.

---

# 32. 최종 Release Gate

- [ ] Java 21 compile PASS
- [ ] `./gradlew clean build` PASS
- [ ] existing verification tasks PASS
- [ ] E16 artifact verification PASS
- [ ] protocol Phase 6/7 fixtures PASS
- [ ] Phase 1 config tests PASS
- [ ] Phase 2 interaction/audience PASS
- [ ] Phase 3 reasoning/Jev fallback PASS
- [ ] Phase 4 style/progress/sound PASS
- [ ] Phase 5 ExecutionPolicy PASS
- [ ] Phase 6 weather/time PASS
- [ ] Phase 7 scheduler PASS
- [ ] Phase 8 ACTIVE PASS
- [ ] Paper gameplay smoke PASS
- [ ] Fabric gameplay smoke PASS
- [ ] NeoForge gameplay smoke PASS
- [ ] clean-server boot 3종 PASS
- [ ] live Jev PASS
- [ ] live Luna PASS
- [ ] non-OP mutation 차단 PASS
- [ ] proactive mutation 차단 PASS
- [ ] scheduling reauthorization PASS
- [ ] audit secret leakage 없음
- [ ] race 테스트에서 중복 mutation 없음
- [ ] shutdown/restart lifecycle 문제 없음

---

# 33. 가장 먼저 실행할 명령 요약

```bash
git checkout codex/phase1-config-foundation
git status

java -version
./gradlew --version

./gradlew :minecraft:common:compileJava --stacktrace
./gradlew :minecraft:paper:compileJava --stacktrace
./gradlew :minecraft:fabric:compileJava --stacktrace
./gradlew :minecraft:neoforge:compileJava --stacktrace

./gradlew :minecraft:common:jarvisConfigVerification --stacktrace
./gradlew :minecraft:common:embeddedBrainVerification --stacktrace
./gradlew :minecraft:common:embeddedBrainParityVerification --stacktrace

./gradlew :minecraft:paper:t06Verification --stacktrace
./gradlew :minecraft:fabric:t07Verification --stacktrace
./gradlew :minecraft:neoforge:t08Verification --stacktrace

./gradlew clean build --stacktrace
./gradlew verifyE16Artifacts --stacktrace
```

여기까지 모두 PASS한 뒤 Phase 1~8 신규 deterministic coverage를 보강하고, clean-server boot → live API → 실제 gameplay E2E 순서로 진행한다.

---

## 결론

권장 순서는:

```text
compile
→ 기존 deterministic regression
→ build
→ Phase 1~8 신규 deterministic coverage
→ clean boot
→ 실제 플랫폼 smoke
→ live AI
→ scheduling/ACTIVE race
→ release gate
```

특히 Phase 7과 Phase 8은 시간·동시성·권한 변화가 관여하므로 단순한 "한 번 정상 동작" 테스트만으로는 충분하지 않다. **취소, de-op, config 변경, logout, timeout, shutdown이 실행 직전 발생하는 경우**를 반드시 검증해야 한다.
