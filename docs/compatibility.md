# T00 호환성·재사용 조사

작성일: 2026-09-24  
기준 문서: [Minecraft_JARVIS_WORK_SPEC.md](Minecraft_JARVIS_WORK_SPEC.md)  
상태: **T00 문서 조사 완료 / T01 착수 가능, 빌드·실서버·유료 API live 검증은 아직 수행하지 않음**

## 1. 조사 범위와 판정 규칙

T00은 구현 단계가 아니다. Minecraft 1.21.8을 첫 검증 기준으로 삼아 플랫폼, 빌드 도구, AI SDK, Paper Provider의 **재사용 가능한 공개 API와 호환 경계**를 고정한다.

상태 표기:

| 상태 | 의미 |
|---|---|
| CONFIRMED | 공식 문서, 공식 Maven/패키지 저장소 또는 upstream 저장소로 존재와 API 계약을 확인함 |
| PINNED | T02 빌드 골격에서 사용할 고정 버전. 실제 멀티모듈 빌드는 아직 하지 않음 |
| RUNTIME UNVERIFIED | 실제 Minecraft 서버 기동/이벤트/플러그인 조합은 아직 검증하지 않음 |
| LIVE UNVERIFIED | 실제 외부 AI 계정/API 호출은 아직 하지 않음 |
| BLOCKED | 라이선스·공개 API·정확한 조합을 더 확인하기 전 기능 활성화 금지 |

## 2. 기준 버전 매트릭스

### 2.1 공통 런타임/빌드

| 항목 | T00 고정값 | 상태 | 근거/메모 |
|---|---:|---|---|
| Minecraft | 1.21.8 | PINNED | 작업명세 기준. 1.21.8 이후 버전의 바이너리 호환을 의미하지 않음 |
| JVM | Java 21 (64-bit) | PINNED | NeoForge 1.21.6-1.21.8 공식 문서가 Java 21을 요구. Paper/Fabric도 동일 toolchain으로 통일 |
| Gradle Wrapper | 8.14.5 | PINNED | Gradle 공식 Releases의 최신 8.14.x 패치. Loom/ModDevGradle과의 실제 동시 빌드는 T02에서 검증 |
| Brain Node.js | Node 24 LTS | PINNED | OpenAI Node SDK 정책상 권장 런타임. TypeSafe SDK의 Node 20+ 요구도 충족 |

Gradle 9 계열이 현재 존재하더라도 T00에서는 채택하지 않는다. 세 Minecraft 빌드 플러그인의 공통 기반을 먼저 안정화하기 위해 8.14.x 최신 패치를 사용하고, 변경은 T02의 실제 빌드 증거로만 허용한다.

### 2.2 Paper

| 항목 | T00 고정값 | 상태 |
|---|---:|---|
| 서버 대상 | Paper 1.21.8 | PINNED / RUNTIME UNVERIFIED |
| compile API | io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT | CONFIRMED |
| Java | 21 | PINNED |
| 채팅 진입점 후보 | AsyncChatEvent | CONFIRMED |
| OP 판정 | Player/ServerOperator.isOp() | CONFIRMED |
| Folia | 미지원 | 고정 요구사항 |

Paper 1.21.8 Javadocs가 1.21.8-R0.1-SNAPSHOT API를 제공한다. Paper 채팅 문서는 AsyncChatEvent가 비동기이며 해당 핸들러에서 Bukkit API를 직접 사용하는 것은 안전하지 않다고 명시한다. 따라서 **채팅 텍스트를 접수/차단하는 작업과 서버 상태·플레이어·월드 접근을 분리**하고, Bukkit 객체 접근은 Paper scheduler를 통해 안전한 서버 스레드에서 수행한다.

OP 권한 근거는 LLM이나 별도 permission node가 아니라 서버가 제공하는 isOp() 결과다. 접수 전/Tool 실행 직전/결과 전달 직전에 재검사한다.

공식 근거:
- https://jd.papermc.io/paper/1.21.8/
- https://docs.papermc.io/paper/dev/chat-events/
- https://jd.papermc.io/paper/1.21.8/org/bukkit/permissions/ServerOperator.html
- https://docs.papermc.io/paper/dev/project-setup/
- https://github.com/PaperMC/Paper/blob/main/LICENSE.md

### 2.3 Fabric

| 항목 | T00 고정값 | 상태 |
|---|---:|---|
| Minecraft | 1.21.8 | PINNED |
| Fabric Loader | 0.17.2 | PINNED / RUNTIME UNVERIFIED |
| Fabric API | 0.133.4+1.21.8 | CONFIRMED / PINNED |
| Yarn | 1.21.8+build.1 | CONFIRMED / PINNED |
| Fabric Loom | 1.12.2 | CONFIRMED / PINNED |
| Java | 21 | PINNED |
| 채팅 진입점 후보 | ServerMessageEvents.ALLOW_CHAT_MESSAGE | CONFIRMED |
| OP 판정 | PlayerManager.isOperator(GameProfile) | CONFIRMED |

Fabric API 0.133.4+1.21.8의 ServerMessageEvents.ALLOW_CHAT_MESSAGE는 플레이어 채팅의 서버 broadcast를 막을 수 있다. JARVIS 직접 호출을 공개 채팅으로 보내지 않기 위한 1차 후보로 채택한다.

주의: 공식 Javadoc은 해당 callback의 스레드 계약을 명시적으로 설명하지 않는다. T07에서는 callback이 실제 dedicated server thread에서 실행되는지 검증하고, **문서로 보장되지 않은 스레드 안전성을 전제로 삼지 않는다.** Minecraft world/player 객체는 서버 실행 큐에서 스냅샷으로 변환한다.

OP 판정은 1.21.8 Yarn의 PlayerManager.isOperator(GameProfile)을 사용한다. permission 레벨만으로 JARVIS 접근을 열지 않는다.

공식 근거:
- https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.133.4%2B1.21.8/
- https://maven.fabricmc.net/docs/fabric-api-0.133.4%2B1.21.8/net/fabricmc/fabric/api/message/v1/ServerMessageEvents.html
- https://maven.fabricmc.net/net/fabricmc/yarn/1.21.8%2Bbuild.1/
- https://maven.fabricmc.net/docs/yarn-1.21.8%2Bbuild.1/net/minecraft/server/PlayerManager.html
- https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.17.2/
- https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.12.2/
- https://github.com/FabricMC/fabric-loader
- https://github.com/FabricMC/fabric-api
- https://github.com/FabricMC/fabric-loom

### 2.4 NeoForge

| 항목 | T00 고정값 | 상태 |
|---|---:|---|
| Minecraft | 1.21.8 | PINNED |
| NeoForge | 21.8.52 | CONFIRMED / PINNED |
| ModDevGradle | 2.0.147 | CONFIRMED / PINNED |
| Java | 21 | CONFIRMED / PINNED |
| 채팅 진입점 후보 | ServerChatEvent | CONFIRMED, 정확한 21.8.52 signature는 T02/T08 compile 검증 |
| OP 판정 | MinecraftServer PlayerList의 operator list | PINNED, 정확한 mapped signature는 T02 compile 검증 |

NeoForge 공식 1.21.6-1.21.8 문서는 Java 21 64-bit를 요구한다. 21.8.52 artifact가 NeoForged 공식 Maven에 존재한다. ModDevGradle 2.0.147은 Gradle Plugin Portal에서 확인했다.

NeoForge의 ServerChatEvent는 logical server에서 동작하고 취소 가능한 이벤트로 유지되고 있다. 다만 T00 조사에서 공식 1.21.8 API 문서의 상세 signature를 충분히 고정하지 못했으므로 T02/T08에서 **21.8.52 소스/compile 결과를 authority로 재검증**한다.

공식 근거:
- https://docs.neoforged.net/docs/1.21.8/gettingstarted/
- https://maven.neoforged.net/releases/net/neoforged/neoforge/21.8.52/
- https://plugins.gradle.org/plugin/net.neoforged.moddev/2.0.147
- https://github.com/neoforged/NeoForge
- https://github.com/neoforged/ModDevGradle

## 3. Brain AI SDK/모델 고정

| 항목 | T00 고정값 | 상태 | 메모 |
|---|---:|---|---|
| OpenAI JS SDK | openai 7.22.0 | CONFIRMED / PINNED | 2026-09-22 immutable release에서 GPT-6 Sol/Luna identifiers 추가 |
| OpenAI 모델 | gpt-6-luna | CONFIRMED / LIVE UNVERIFIED | Responses API + function calling 사용 |
| reasoning effort | medium | PINNED | Luna 공식 기본값이지만 동작 재현성을 위해 명시 |
| TypeSafe JS SDK | @typesafe-ai/sdk 0.6.0 | CONFIRMED / PINNED |
| TypeSafe 모델 | jev-1.13.0 | CONFIRMED / LIVE UNVERIFIED | alias가 아니라 versioned ID 고정 |
| Brain Node | 24 LTS | PINNED | OpenAI 권장 + TypeSafe Node 20+ 만족 |

OpenAI GPT-6 Luna 공식 모델 페이지는 Responses API의 function calling을 지원하며 reasoning.effort로 none/low/medium/high/xhigh/max를 지원한다. T00은 medium으로 고정한다.

OpenAI SDK 7.22.0은 GPT-6 Luna 식별자를 명시적으로 추가한 첫 확인 릴리스이므로 이 버전으로 고정한다. 자동 minor/major 업그레이드는 금지한다.

TypeSafe Models 문서는 현재 안정 Jev를 jev-1.13.0으로 명시하고 jev-latest가 해당 버전을 가리킨다고 설명한다. alias는 향후 이동할 수 있으므로 제품 평가지표가 달라지지 않도록 versioned ID를 사용한다. 한국어/CJK는 영어 대비 성능이 동일하다고 보장되지 않으므로 A09 평가 전 confidence threshold를 안전 정책으로 사용하지 않는다.

TypeSafe SDK 0.6.0은 2026-09-15 공개된 초기 계열 SDK이며 최근 issue가 존재한다. SDK 자체 retry와 JARVIS retry를 중첩하지 않고, 3초 deadline·조회 전용 fallback·변경 도구 미노출 정책을 유지한다.

공식 근거:
- https://developers.openai.com/api/docs/models/gpt-6-luna
- https://developers.openai.com/api/docs/guides/function-calling
- https://github.com/openai/openai-node/releases/tag/v7.22.0
- https://github.com/openai/openai-node/blob/main/NODE_VERSION_POLICY.md
- https://docs.typesafe.ai/models
- https://docs.typesafe.ai/sdk/javascript
- https://github.com/typesafe-ai/typesafe-sdk-js/releases/tag/v0.6.0
- https://www.npmjs.com/package/%40typesafe-ai/sdk

**Live 상태:** OPENAI_API_KEY/TYPESAFE_API_KEY를 사용한 유료 live 호출은 T00에서 수행하지 않았다. A10은 T05/T10에서 별도 증거로 남긴다.

## 4. Paper Provider 재사용 조사

### 4.1 CoreProtect

| 항목 | 판정 |
|---|---|
| T00 compile target | net.coreprotect:coreprotect:24.0, provided/compileOnly |
| API | v12 |
| API 호환 범위 | CoreProtect 24.0+ |
| 현재 사용자 서버 예시 | CoreProtect 24.1 |
| 라이선스 | Artistic-2.0 |
| v0.1 | 미사용 |
| v0.1.1 | 조회만 사용 |
| v0.2 | 승인형 rollback 후보, 별도 계약 필요 |

CoreProtect 공식 API v12 문서는 plugin 24.0+를 요구하고 APIVersion() >= 12와 isEnabled() 확인 예제를 제공한다. JARVIS는 plugin 존재만으로 capability를 켜지 않고 **플러그인 타입 + API enabled + APIVersion >= 12**를 확인한다.

performRollback()은 API v12 문서에서 async 호출을 요구한다. v0.1.1은 read-only lookup만 포함하지만 DB 조회를 tick thread에서 기다리지 않는다는 공통 원칙을 적용한다. Bukkit Block/Location/Player에서 필요한 값은 안전한 서버 스레드에서 제한된 DTO로 캡처한 뒤, Provider의 DB 작업을 별도 executor로 넘긴다.

공식 근거:
- https://docs.coreprotect.net/api/
- https://docs.coreprotect.net/api/version/v12/
- https://github.com/PlayPro/CoreProtect

### 4.2 WorldGuard

| 항목 | 판정 |
|---|---|
| T00 기준 | WorldGuard 7.0.18 |
| API major | 7.x |
| 필수 companion | WorldEdit |
| 라이선스 | LGPL-3.0-or-later |
| v0.1 | 미사용 |
| v0.1.1 | region/flags/build protection 조회 |
| 변경 작업 | 미지원 |

현재 사용자 서버 예시가 WorldGuard 7.0.18이므로 JARVIS의 첫 runtime target도 7.0.18로 맞춘다. EngineHub는 7.x 안에서 API 안정성을 높게 유지한다고 문서화한다.

보호 판정은 직접 owner/priority/flag를 재구현하지 않고 RegionQuery.testState를 사용한다. WorldGuard 문서는 **RegionQuery가 bypass permission을 자동 확인하지 않는다**고 경고하므로 요청자의 bypass가 의미 있는 질문에서는 SessionManager.hasBypass를 별도로 확인해야 한다.

WorldGuard API 자료구조가 thread-safe하더라도 Bukkit Player/World adapter와 server 객체 접근은 같은 것으로 취급하지 않는다. v0.1.1 초기 구현은 해당 adaptation/query를 서버 스레드에서 짧게 수행하고 즉시 DTO로 변환한다. 실제 비용이 문제가 될 때만 공식 thread 계약과 부하 측정을 근거로 분리한다.

공식 근거:
- https://worldguard.enginehub.org/en/latest/developer/dependency/
- https://worldguard.enginehub.org/en/latest/developer/regions/protection-query/
- https://github.com/EngineHub/WorldGuard

### 4.3 CMI

| 항목 | 판정 |
|---|---|
| 사용자 서버 예시 | CMI 9.8.9.6 + CMILib 1.5.9.9 |
| 공개 CMI-API 문서 버전 | 9.8.6.4 |
| dependency 방식 | JitPack + provided |
| 라이선스 | **미확인 / BLOCKED** |
| v0.1/v0.1.1 | 미사용 |
| v0.2 T14 | 선택 확장, smoke test 후 capability 활성화 |

CMI 공식 API 페이지는 CMI-API 9.8.6.4를 provided dependency로 안내한다. 그러나 사용자가 보유한 CMI runtime은 9.8.9.6이며, **공개 API artifact와 해당 runtime 조합의 호환을 공식 문서만으로 증명하지 못했다.**

또한 CMI-API GitHub 저장소에서 명시적 LICENSE 파일/표기를 T00에서 확인하지 못했다. 따라서 CMI/CMI-API binary를 JARVIS artifact에 재배포하지 않는다. T14 전에 라이선스/재배포 조건을 확인하고, compileOnly/provided 방식만 고려한다.

CMI 문서는 offline player 정보 로드가 대량 실행 시 서버에 부담을 줄 수 있다고 경고한다. JARVIS는 bulk offline scan을 하지 않는다. API의 일반 thread-safety 계약도 명시되지 않았으므로 Bukkit/CMI 객체 접근은 서버 스레드로 제한한다.

공식 근거:
- https://www.zrips.net/cmi/api/
- https://github.com/Zrips/CMI-API

## 5. 라이선스·재배포 정책

| 구성요소 | upstream 라이선스 | JARVIS 처리 |
|---|---|---|
| Paper | GPL-3.0 계열, 일부 contributor code MIT | compileOnly, Paper 자체 미번들 |
| Fabric Loader | Apache-2.0 | loader 미번들 |
| Fabric API | Apache-2.0 | 플랫폼 mod dependency로 선언 |
| Fabric Loom | MIT | 빌드 전용 |
| NeoForge | LGPL-2.1 | 플랫폼 dependency, NeoForge 자체 미번들 |
| ModDevGradle | upstream 조건 준수 | 빌드 전용 |
| OpenAI JS SDK | Apache-2.0 | Brain npm dependency |
| TypeSafe JS SDK | MIT | Brain npm dependency |
| CoreProtect | Artistic-2.0 | provided/compileOnly, plugin 미번들 |
| WorldGuard | LGPL-3.0-or-later | compileOnly, plugin 미번들 |
| CMI / CMI-API | CMI는 상용 plugin, CMI-API 저장소 라이선스 T00 미확인 | **미번들**, T14 전 조건 확인 |

이 표는 법률 자문이 아니라 빌드/배포 경계 결정이다. JARVIS release artifact에 타사 서버/plugin/mod binary를 shade하지 않는 것을 기본값으로 한다.

## 6. 스레드·권한 경계

| 대상 | T00 정책 |
|---|---|
| AI HTTP/WebSocket/disk/DB | Minecraft tick thread에서 대기 금지 |
| Paper AsyncChatEvent | 공개 전달 차단/메시지 캡처만; Bukkit API 접근은 scheduler로 넘김 |
| Fabric chat callback | 공식 thread 명시 부족. 서버 객체 접근은 server execute/scheduler 경계 안에서 수행 |
| NeoForge ServerChatEvent | logical server event. world/player state 접근·변경은 server execution context에서만 수행 |
| CoreProtect DB lookup | server-thread snapshot 후 비동기 Provider 작업 |
| CoreProtect rollback | v0.2 전 미지원; API 요구대로 async + 승인/저널 계약 필요 |
| WorldGuard query | 초기에는 server-thread short query → DTO; bypass 별도 검사 |
| CMI | 문서상 일반 thread-safety 미확인. server-thread only로 시작 |
| OP 검증 | 모델 값 금지. 플랫폼의 실제 operator registry/API를 authoritative source로 사용 |

플랫폼/NMS/Bukkit/WorldGuard/CMI 객체를 Brain이나 공통 Java 모듈로 넘기지 않는다. 비동기 경계 밖에는 JARVIS DTO만 보관한다.

## 7. T01/T02에 넘기는 고정값

T01은 아래를 계약 전제로 사용할 수 있다.

- 첫 protocol/runtime target: Minecraft 1.21.8, Java 21.
- Brain: Node 24, OpenAI 7.22.0, gpt-6-luna + reasoning medium, @typesafe-ai/sdk 0.6.0, jev-1.13.0.
- OP authority: Paper isOp(), Fabric PlayerManager.isOperator(GameProfile), NeoForge vanilla operator list.
- 외부 모델/Brain이 보내는 isOp 값은 권한 근거가 아니다.
- Provider가 없거나 version/API check가 실패하면 capability/tool 자체를 노출하지 않는다.
- CMI는 T14 전까지 Tool catalog에 넣지 않는다.

T02는 다음 고정값으로 build skeleton을 시도한다.

- Gradle Wrapper 8.14.5
- Paper API 1.21.8-R0.1-SNAPSHOT
- Fabric Loader 0.17.2
- Fabric API 0.133.4+1.21.8
- Yarn 1.21.8+build.1
- Fabric Loom 1.12.2
- NeoForge 21.8.52
- ModDevGradle 2.0.147

T02에서 실제 dependency resolution/compile이 실패하면 **가장 작은 버전 조정만** 수행하고, 이 문서와 ADR을 함께 갱신한다.

## 8. 미확인 항목 / 후속 검증 게이트

1. Paper/Fabric/NeoForge 1.21.8 dedicated server의 실제 기동과 동일 채팅 UX.
2. Fabric ALLOW_CHAT_MESSAGE 취소 시 1.21.8 client에 서명 채팅 관련 잔여 표시가 없는지.
3. NeoForge 21.8.52의 ServerChatEvent 및 operator 판정 정확한 compile signature.
4. 세 플랫폼에서 deop/logout race가 실행 직전 재검사로 차단되는지.
5. Gradle 8.14.5 + Loom 1.12.2 + ModDevGradle 2.0.147 멀티프로젝트 동시 build.
6. CoreProtect 24.1 runtime에서 API v12 lookup paging/timeout과 실제 DB executor 동작.
7. WorldGuard 7.0.18 + 실제 WorldEdit/FAWE 조합에서 RegionQuery와 bypass 결과.
8. CMI 9.8.9.6 + CMI-API 9.8.6.4의 실제 binary/runtime 호환 및 라이선스/재배포 조건.
9. 실제 OpenAI 계정에서 gpt-6-luna Responses Tool call/result.
10. 실제 TypeSafe 계정에서 jev-1.13.0 모델 ID/분류 응답/request ID.
11. 한국어 Jev A09 평가 200건 이상. confidence threshold는 그 전까지 정책 권한으로 사용하지 않음.

## 9. T00 인계

- 선행 커밋: 878ce755438ad5313b58bbbb6ef088ef6bc38b0b
- 작업 브랜치: codex/t00-compatibility
- 변경 범위: docs/compatibility.md, docs/decisions/*
- 구현 코드 변경: 없음
- 유료 API 호출: 없음
- Minecraft 서버 기동: 없음
- 결론: **문서 계약 기준 T00 완료. T01 계약·스키마 작업을 시작할 수 있음.**
