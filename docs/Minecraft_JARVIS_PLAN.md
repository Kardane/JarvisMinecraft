# Minecraft JARVIS 기획서

> **2026-09-23 검토본 안내:** 아래 본문은 초기 구상을 보존한 문서입니다. 구현 범위·보안 정책·일정이 충돌하면 [병렬 작업명세서](Minecraft_JARVIS_WORK_SPEC.md)를 우선합니다. 변경 이유는 [검토 보고서](Minecraft_JARVIS_REVIEW.md)를 참고하세요. 확정 제약은 **Minecraft 최소 1.21.8 / 초기 OP 전용 / GPT-6 Luna + TypeSafe AI Jev / 기존 SDK·플러그인 API 재사용**입니다. 초기 구현에서는 롤백·경고 조치를 제외하고, OP 비공개 응답은 v0.1부터 제공합니다.

> Minecraft 서버 운영진을 위한 자연어 기반 AI 운영 보조 시스템  
> 대상 플랫폼: **Paper / Fabric / NeoForge**  
> 기본 LLM: **OpenAI GPT-6 Luna (`gpt-6-luna`)**

---

## 1. 프로젝트 개요

### 1.1 프로젝트명

**Minecraft JARVIS**

### 1.2 한 줄 설명

운영자가 명령어를 외우지 않아도 일반 플레이어에게 말하듯 채팅하면,  
서버 상태·플레이어·로그·보호구역·변경 이력을 이해하고 필요한 정보를 조회하거나 운영 작업을 보조하는 AI 에이전트.

예:

```text
Admin: 자비스 지금 서버 어때?

JARVIS:
현재 TPS 19.8, MSPT 21ms입니다.
접속자는 37명이고 즉시 확인할 수준의 이상 징후는 없습니다.
```

```text
Admin: 자비스 여기 누가 부쉈어?

JARVIS:
현재 위치 반경 10블록을 확인했습니다.
최근 30분 동안 Player_A가 43개 블록을 파괴했습니다.
```

```text
Admin: 그 사람 지금 어디 있어?

JARVIS:
Player_A는 현재 world의 x: 183, y: 64, z: -721에 있습니다.

Admin: 나 거기로 보내줘.

JARVIS:
텔레포트하겠습니다.
```

---

# 2. 프로젝트 목표

Minecraft JARVIS의 목적은 AI가 서버를 대신 운영하는 것이 아니라,  
**운영자가 서버를 더 빠르고 정확하게 이해하고 관리하도록 보조하는 것**이다.

핵심 목표:

1. 자연어 채팅으로 서버 정보 조회
2. 이전 대화 맥락을 유지하는 연속 대화
3. 서버 상태 및 플레이어 상태 조회
4. Paper 서버에서는 기존 운영 플러그인과 연동
5. Fabric / NeoForge에서도 핵심 기능 사용 가능
6. 위험한 운영 작업은 반드시 권한 및 승인 절차 적용
7. AI 호출이 Minecraft 메인 스레드를 방해하지 않도록 완전 비동기화
8. 특정 Minecraft 플랫폼 API에 JARVIS Core가 종속되지 않도록 설계

---

# 3. 지원 플랫폼

## 3.1 Paper

배포 파일:

```text
jarvis-paper.jar
```

지원 예정:

- Paper Chat API
- 플레이어 조회
- 텔레포트
- 서버 상태
- CoreProtect
- WorldGuard
- CMI

---

## 3.2 Fabric

배포 파일:

```text
jarvis-fabric.jar
```

지원 예정:

- Fabric 서버 이벤트
- 채팅 감지
- 플레이어 조회
- 텔레포트
- 서버 상태
- 서버 기본 정보

선택적 확장:

- CoreProtect Fabric 연동
- 별도 History Provider
- 별도 Region Provider

---

## 3.3 NeoForge

배포 파일:

```text
jarvis-neoforge.jar
```

지원 예정:

- NeoForge Server Events
- 채팅 감지
- 플레이어 조회
- 텔레포트
- 서버 상태
- 서버 기본 정보

---

# 4. 전체 아키텍처

```text
                       Minecraft Server
                              │
          ┌───────────────────┼───────────────────┐
          │                   │                   │
    Paper Adapter       Fabric Adapter      NeoForge Adapter
          │                   │                   │
          └───────────────────┼───────────────────┘
                              │
                       Jarvis Common API
                              │
                              │ WebSocket / IPC
                              ▼
                    ┌────────────────────┐
                    │    JARVIS Brain    │
                    │                    │
                    │ GPT-6 Luna         │
                    │ Conversation       │
                    │ Tool Router        │
                    │ Context Manager    │
                    │ Permission Logic   │
                    │ Audit              │
                    └─────────┬──────────┘
                              │
                              ▼
                       Minecraft Tools
```

---

# 5. 설계 원칙

## 5.1 Minecraft와 AI 분리

LLM을 Minecraft 서버 프로세스 내부에서 직접 호출하지 않는다.

```text
Minecraft JVM
     │
     │ localhost WebSocket
     ▼
Jarvis Brain
```

장점:

- LLM 응답 지연이 Minecraft tick에 영향을 주지 않음
- AI 프로세스 재시작이 Minecraft 서버에 영향 없음
- API Key를 서버 모드/플러그인 JAR에 직접 포함하지 않아도 됨
- 여러 Minecraft 플랫폼이 동일한 Brain을 사용할 수 있음
- 향후 LLM 교체가 쉬움

---

## 5.2 Platform Adapter 패턴

JARVIS Core는 아래 API를 직접 import하지 않는다.

```text
org.bukkit.*
net.fabricmc.*
net.neoforged.*
```

대신 자체 인터페이스만 사용한다.

예:

```java
public interface PlayerService {
    Optional<JarvisPlayer> findPlayer(String name);
    Optional<JarvisLocation> getLocation(UUID player);
    boolean isOnline(UUID player);
    CompletableFuture<Void> teleport(UUID player, JarvisLocation target);
}
```

플랫폼별 구현:

```text
PlayerService
 ├─ PaperPlayerService
 ├─ FabricPlayerService
 └─ NeoForgePlayerService
```

---

# 6. Paper 플러그인 연동

v1에서 공식 지원할 외부 플러그인은 아래 3개로 제한한다.

```text
CoreProtect
WorldGuard
CMI
```

다른 플러그인은 초기 범위에서 제외한다.

---

## 6.1 CoreProtect

역할:

**JARVIS의 서버 행동 기록 조회 시스템**

예:

```text
Admin: 자비스 여기 누가 설치했어?

JARVIS:
최근 1시간 동안 이 위치 반경 10블록에서
Player_A가 37개 블록을 설치했습니다.
```

추상화:

```java
public interface HistoryProvider {

    CompletableFuture<List<BlockChange>> findBlockChanges(
        JarvisLocation center,
        int radius,
        Duration since
    );

    CompletableFuture<List<PlayerAction>> findPlayerActions(
        UUID player,
        Duration since
    );

    boolean supportsRollback();

    CompletableFuture<RollbackPreview> previewRollback(
        RollbackRequest request
    );

    CompletableFuture<RollbackResult> rollback(
        RollbackRequest request
    );
}
```

Paper 구현:

```text
HistoryProvider
      │
      └─ CoreProtectHistoryProvider
              │
              └─ CoreProtect API
```

### 초기 지원 기능

- 블록 설치 기록
- 블록 파괴 기록
- 특정 플레이어 변경 기록
- 특정 위치 변경 기록
- 특정 시간 범위 조회
- Rollback Preview
- 승인 후 Rollback

### 원칙

AI가 임의로 rollback을 실행하지 않는다.

```text
조회                 자동 가능
Rollback Preview     자동 가능
Rollback 실행         운영자 승인 필요
```

---

# 7. WorldGuard

역할:

**JARVIS가 현재 위치의 의미와 보호 정책을 이해하도록 하는 시스템**

예:

```text
Admin: 자비스 여기가 무슨 구역이야?

JARVIS:
현재 위치는 `spawn_market` 지역에 포함되어 있습니다.
BUILD는 일반 플레이어에게 허용되지 않습니다.
```

추상화:

```java
public interface RegionProvider {

    List<RegionInfo> getRegions(JarvisLocation location);

    Optional<RegionInfo> getRegion(String id);

    ProtectionResult canBuild(
        UUID player,
        JarvisLocation location
    );
}
```

초기 지원:

- 위치의 Region 목록
- Region ID
- Priority
- Owner / Member
- 주요 Flags
- 특정 플레이어의 build 가능 여부

Fabric / NeoForge에서 RegionProvider가 없는 경우:

```text
region.lookup capability = false
```

AI에게 해당 Tool을 노출하지 않는다.

---

# 8. CMI

역할:

**Paper 서버의 운영 기능 및 플레이어 정보 Provider**

초기 대상:

- 온라인 여부
- 플레이어 위치
- 플레이어 상태
- 텔레포트
- vanish 상태
- warning 관련 정보
- CMI에서 안정적으로 제공 가능한 운영 정보

JARVIS가 자유롭게 CMI 명령어 문자열을 생성하여 콘솔에서 실행하게 하지 않는다.

금지:

```text
LLM
 ↓
"cmi ban Player ..."
 ↓
dispatchCommand(...)
```

허용:

```java
playerService.teleport(...);
moderationService.warn(...);
```

즉 모든 서버 작업은 사전에 정의된 Tool로만 수행한다.

---

# 9. GPT-6 Luna

## 9.1 기본 모델

```text
Provider: OpenAI
Model: gpt-6-luna
API: Responses API
```

GPT-6 Luna를 Minecraft JARVIS의 기본 LLM으로 사용한다.

주요 용도:

- 사용자 의도 분석
- 자연어 응답 생성
- Tool 선택
- Tool argument 생성
- 대화 Context 해석
- 조회 결과 요약
- 서버 상태 설명
- 여러 Tool 결과의 종합

---

## 9.2 LLM이 직접 해서는 안 되는 것

GPT-6 Luna는 다음 작업을 직접 수행하지 않는다.

- Minecraft API 직접 호출
- Java 코드 실행
- 임의 Console Command 실행
- 임의 SQL 실행
- CoreProtect DB 직접 접근
- WorldGuard 내부 데이터 직접 수정
- 운영자 승인 없는 고위험 조치

항상 다음 흐름을 사용한다.

```text
Player Chat
    ↓
GPT-6 Luna
    ↓
Structured Tool Call
    ↓
Permission Check
    ↓
Minecraft Adapter
    ↓
Result
    ↓
GPT-6 Luna
    ↓
Chat Response
```

---

# 10. Chat Agent

명령어 중심 UX를 사용하지 않는다.

기본 인터페이스는 일반 Minecraft 채팅이다.

예:

```text
Admin: 자비스

JARVIS: 네.
```

```text
Admin: 자비스 Steve 어디 있어?

JARVIS:
Steve는 현재 world의 x:120, y:63, z:-84에 있습니다.
```

---

## 10.1 호출 방식

기본 aliases:

```yaml
assistant:
  name: "JARVIS"

  aliases:
    - "자비스"
    - "jarvis"
    - "재비스"
```

다음 상황에 반응한다.

### 직접 호출

```text
자비스 서버 상태 어때?
```

### 활성 대화 Context

```text
Admin: 자비스 Steve 어디 있어?
JARVIS: 광산에 있습니다.

Admin: 정확한 좌표는?
```

두 번째 메시지에 `자비스`가 없어도 일정 시간 동안 대화를 유지한다.

---

## 10.2 Conversation Context

예:

```ts
interface ConversationContext {
    playerId: string;

    active: boolean;

    lastMessages: ChatMessage[];

    subject?: {
        type: "PLAYER" | "LOCATION" | "INCIDENT" | "REGION";
        id: string;
    };

    expiresAt: number;
}
```

기본 Conversation TTL:

```text
120초
```

설정에서 변경 가능하게 한다.

---

# 11. Tool 시스템

JARVIS가 사용할 수 있는 모든 서버 기능은 Tool로 정의한다.

## 11.1 공통 Tool

```text
get_server_status
get_online_players
get_player
get_player_location
get_nearby_players
teleport_staff
get_world_info
```

---

## 11.2 CoreProtect Tool

```text
lookup_block_history
lookup_player_history
lookup_area_history
preview_rollback
execute_rollback
```

---

## 11.3 WorldGuard Tool

```text
get_regions_at_location
get_region_info
get_region_flags
check_build_permission
```

---

## 11.4 CMI Tool

```text
get_cmi_player_info
get_vanish_state
teleport_staff
get_warning_info
warn_player
```

CMI Tool은 실제 API 안정성 확인 후 세부 목록을 확정한다.

---

# 12. Capability 시스템

서버 시작 시 실제 사용 가능한 기능을 자동 탐지한다.

예:

```text
[JARVIS] Platform: Paper

[JARVIS] Integrations
✓ CoreProtect
✓ WorldGuard
✓ CMI

[JARVIS] Capabilities
✓ player.lookup
✓ player.teleport
✓ server.metrics
✓ history.lookup
✓ history.rollback
✓ region.lookup
✓ region.flags
✓ cmi.player
```

Fabric:

```text
[JARVIS] Platform: Fabric

[JARVIS] Capabilities
✓ player.lookup
✓ player.teleport
✓ server.metrics
✗ history.lookup
✗ region.lookup
```

AI에게는 **현재 활성화된 Tool만 전달한다.**

따라서 지원되지 않는 기능을 AI가 존재한다고 가정하는 것을 방지한다.

---

# 13. 위험도 및 승인 시스템

Tool마다 Risk Level을 설정한다.

```text
READ_ONLY
LOW
MEDIUM
HIGH
CRITICAL
```

예:

| Tool | 위험도 | 정책 |
|---|---|---|
| get_server_status | READ_ONLY | 자동 실행 |
| get_player_location | READ_ONLY | 자동 실행 |
| lookup_block_history | READ_ONLY | 자동 실행 |
| teleport_staff | LOW | 자동 실행 가능 |
| warn_player | MEDIUM | 운영자 요청 필요 |
| preview_rollback | MEDIUM | 자동 실행 |
| execute_rollback | HIGH | 명시적 승인 필요 |
| ban_player | CRITICAL | v1 비활성화 |

---

# 14. 승인 대화

예:

```text
Admin: 자비스 Player_A가 방금 부순 거 복구해줘.

JARVIS:
최근 10분 동안 Player_A가 변경한 블록 83개가 복구 대상입니다.

복구를 실행할까요?

Admin: 응.

JARVIS:
복구했습니다.
```

중요:

두 번째 `응`은 반드시 동일 Conversation Context와 승인 Token에 연결한다.

```text
PendingAction
 ├─ requester UUID
 ├─ action
 ├─ arguments
 ├─ preview
 ├─ createdAt
 └─ expiresAt
```

다른 사용자의 `응`으로 실행되어서는 안 된다.

---

# 15. 권한

플랫폼 독립적으로 내부 권한명을 정의한다.

```text
jarvis.chat
jarvis.query.server
jarvis.query.player
jarvis.query.history
jarvis.query.region

jarvis.action.teleport
jarvis.action.warn
jarvis.action.rollback

jarvis.admin
```

Paper에서는 Bukkit Permission API를 사용한다.

Fabric / NeoForge에서는 별도의 permission adapter를 제공한다.

외부 권한 모드와의 연동은 추후 확장한다.

---

# 16. 서버 상태

공통 ServerMetricsProvider를 정의한다.

```java
public interface ServerMetricsProvider {

    double getTPS();

    double getMSPT();

    int getOnlinePlayers();

    int getLoadedChunks();

    long getUsedMemory();

    long getMaxMemory();
}
```

플랫폼마다 가능한 지표 범위가 다를 수 있으므로 Optional / Capability 기반으로 처리한다.

예:

```text
Admin: 자비스 서버 상태 알려줘.

JARVIS:
접속자 38명
TPS 19.92
MSPT 18.4ms
메모리 6.3 / 12GB

현재 성능상 특이사항은 없습니다.
```

---

# 17. 데이터 흐름

```text
Minecraft Chat Event
        │
        ▼
Platform ChatBridge
        │
        ▼
Mention / Context Detector
        │
        ▼
Jarvis Brain
        │
        ▼
GPT-6 Luna
        │
        ├── 응답 가능 ──────────────► Response
        │
        └── Tool 필요
               │
               ▼
          Tool Request
               │
               ▼
        Permission / Risk Check
               │
               ▼
        Minecraft Adapter
               │
               ▼
           Tool Result
               │
               ▼
          GPT-6 Luna
               │
               ▼
       Minecraft Chat Response
```

---

# 18. 통신 프로토콜

Minecraft Adapter와 Brain은 기본적으로 localhost WebSocket으로 통신한다.

예:

```json
{
  "type": "chat.message",
  "requestId": "uuid",
  "serverId": "main",
  "player": {
    "uuid": "...",
    "name": "Admin"
  },
  "message": "자비스 Steve 어디 있어?"
}
```

Tool call:

```json
{
  "type": "tool.request",
  "requestId": "uuid",
  "tool": "get_player_location",
  "arguments": {
    "player": "Steve"
  }
}
```

응답:

```json
{
  "type": "tool.result",
  "requestId": "uuid",
  "success": true,
  "data": {
    "world": "world",
    "x": 183.4,
    "y": 64,
    "z": -721.8
  }
}
```

---

# 19. 보안

## 필수 규칙

### API Key

Minecraft config에 OpenAI API Key를 평문으로 넣지 않는 것을 권장한다.

Brain 프로세스 환경 변수:

```text
OPENAI_API_KEY
```

사용.

### Tool Allowlist

LLM이 호출할 수 있는 Tool은 서버가 등록한 Tool로 제한한다.

### Console Command 금지

v1에서는 generic command execution tool을 제공하지 않는다.

즉 아래 Tool은 존재하지 않는다.

```text
run_console_command(command)
```

### SQL 금지

LLM에게 DB query 기능을 제공하지 않는다.

### Audit Log

모든 Tool 호출 기록:

```text
timestamp
requester
tool
arguments
result
approval
latency
```

---

# 20. 로그 및 감사 기록

파일 예:

```text
logs/jarvis-audit-2026-09-23.jsonl
```

예:

```json
{
  "time": "2026-09-23T22:31:24+09:00",
  "player": "Admin",
  "tool": "lookup_block_history",
  "risk": "READ_ONLY",
  "success": true
}
```

고위험 Action은 before / after를 별도 기록한다.

---

# 21. 설정 파일

Paper 예시:

```yaml
jarvis:
  enabled: true

assistant:
  name: "JARVIS"
  aliases:
    - "자비스"
    - "jarvis"

  conversation-timeout-seconds: 120

brain:
  host: "127.0.0.1"
  port: 17321
  secret: "CHANGE_ME"

integrations:

  coreprotect:
    enabled: true

  worldguard:
    enabled: true

  cmi:
    enabled: true

actions:

  teleport:
    enabled: true

  warning:
    enabled: false

  rollback:
    enabled: true
    require-confirmation: true
```

Brain:

```yaml
openai:
  model: "gpt-6-luna"
  reasoning_effort: "medium"

conversation:
  max_history_messages: 20

tools:
  timeout_ms: 5000
```

---

# 22. 프로젝트 구조

```text
minecraft-jarvis/
│
├─ README.md
├─ docs/
│  ├─ architecture.md
│  ├─ protocol.md
│  └─ tools.md
│
├─ minecraft/
│  │
│  ├─ common/
│  │  └─ src/main/java/
│  │
│  ├─ paper/
│  │  ├─ src/main/java/
│  │  └─ integrations/
│  │     ├─ coreprotect/
│  │     ├─ worldguard/
│  │     └─ cmi/
│  │
│  ├─ fabric/
│  │  └─ src/main/java/
│  │
│  └─ neoforge/
│     └─ src/main/java/
│
├─ brain/
│  ├─ src/
│  │  ├─ ai/
│  │  ├─ conversation/
│  │  ├─ context/
│  │  ├─ protocol/
│  │  ├─ tools/
│  │  ├─ approvals/
│  │  └─ audit/
│  │
│  ├─ package.json
│  └─ tsconfig.json
│
└─ protocol/
   ├─ schemas/
   └─ README.md
```

---

# 23. 기술 스택

## Minecraft

```text
Language:
Java 21 또는 대상 Minecraft 버전에 맞는 JVM 버전

Build:
Gradle Kotlin DSL
```

Platform:

```text
Paper
Fabric API
NeoForge
```

---

## Brain

```text
Node.js
TypeScript
OpenAI SDK
WebSocket
SQLite (초기)
```

향후:

```text
PostgreSQL
Redis
Web Dashboard
Discord
```

---

# 24. v0.1 목표

**"Minecraft 채팅으로 JARVIS와 대화하고 서버 정보를 조회할 수 있다."**

지원:

```text
✓ Paper
✓ Fabric
✓ NeoForge

✓ 채팅 수신
✓ JARVIS 호출 감지
✓ GPT-6 Luna 연결
✓ 연속 대화
✓ 온라인 플레이어 조회
✓ 플레이어 위치
✓ 서버 상태
✓ 운영자 텔레포트
✓ Tool Calling
✓ Audit Log
```

Paper 추가:

```text
✓ CoreProtect 조회
✓ WorldGuard 조회
✓ CMI 플레이어 정보
```

아직 제외:

```text
✗ 자동 제재
✗ 자동 Ban
✗ 임의 Console Command
✗ 자동 Rollback
✗ NPC
✗ 웹 대시보드
```

---

# 25. v0.2 목표

```text
✓ CoreProtect Rollback Preview
✓ 승인 후 Rollback

✓ WorldGuard 상세 분석

✓ CMI 운영 기능 확대

✓ 운영진 전용 응답

✓ 서버 이상 징후 분석

✓ 사건 Context
```

예:

```text
Admin: 자비스 아까 렉 왜 걸렸어?

JARVIS:
21:13경 엔티티 수가 급증했습니다.
가장 큰 증가는 Nether의 x:1800, z:-700 인근에서 발생했습니다.
```

---

# 26. v0.3 목표

```text
✓ 선제적 운영진 알림
✓ Incident Detector
✓ Staff Chat
✓ Discord Bridge
✓ 장기 서버 통계
✓ Web Dashboard
```

---

# 27. 향후 확장

### Citizens NPC

Paper 환경에서 Citizens가 존재하면 JARVIS 아바타 제공.

```text
Admin: 자비스 이리 와.

[JARVIS NPC 이동]
```

초기 범위에는 포함하지 않는다.

---

### History Provider 확대

```text
HistoryProvider
 ├─ CoreProtect Paper
 ├─ CoreProtect Fabric
 └─ Jarvis Native History
```

---

### Region Provider 확대

```text
RegionProvider
 ├─ WorldGuard
 └─ Jarvis Native Regions
```

---

### AI Routing

초기에는 GPT-6 Luna 하나만 사용한다.

향후 트래픽이 커지면:

```text
Rule Engine
    ↓
Small Decision Model
    ↓
GPT-6 Luna
```

형태로 비용 및 latency 최적화 가능.

---

# 28. 핵심 UX

JARVIS는 "명령어 시스템"이 아니라 "운영진 동료"처럼 느껴져야 한다.

```text
Admin: 자비스

JARVIS: 네.

Admin: 오늘 서버 괜찮았어?

JARVIS:
전체적으로 정상입니다.

현재 42명 접속 중이고 TPS는 19.8입니다.
오늘 확인할 만한 이벤트는 두 건 있었습니다.

Admin: 어떤 건데?

JARVIS:
첫 번째는 18:42경 특정 구역에서 TNT 설치가 집중된 사건이고,
두 번째는 약 20분 전 Nether의 엔티티 증가입니다.

Admin: TNT 건 누군데?

JARVIS:
CoreProtect 기록상 Player_A가 해당 시간 동안
TNT 63개를 설치했습니다.

Admin: 지금 있어?

JARVIS:
네. 현재 접속 중입니다.

Admin: 어디 있는데?

JARVIS:
world의 x:183, y:64, z:-721입니다.

Admin: 나 보내줘.

JARVIS:
텔레포트하겠습니다.
```

이 대화가 Minecraft JARVIS의 제품 경험 기준이다.

---

# 29. Definition of Done — v0.1

v0.1 완료 조건:

- [ ] Paper 서버에서 JARVIS가 채팅을 수신한다.
- [ ] Fabric 서버에서 JARVIS가 채팅을 수신한다.
- [ ] NeoForge 서버에서 JARVIS가 채팅을 수신한다.
- [ ] `자비스` 호출을 인식한다.
- [ ] 2분 내 연속 질문의 Context를 유지한다.
- [ ] GPT-6 Luna Responses API와 연결된다.
- [ ] Tool Calling이 동작한다.
- [ ] 온라인 플레이어를 조회할 수 있다.
- [ ] 플레이어 위치를 조회할 수 있다.
- [ ] 서버 상태를 조회할 수 있다.
- [ ] 운영자를 대상 플레이어 위치로 텔레포트할 수 있다.
- [ ] Paper + CoreProtect 조회가 동작한다.
- [ ] Paper + WorldGuard 조회가 동작한다.
- [ ] Paper + CMI 연동이 동작한다.
- [ ] 존재하지 않는 Integration Tool을 AI에게 노출하지 않는다.
- [ ] Minecraft 메인 스레드에서 LLM HTTP 요청을 하지 않는다.
- [ ] generic console command 실행 기능이 없다.
- [ ] 모든 Tool 실행을 Audit Log에 기록한다.
- [ ] 위험 작업은 명시적 승인 절차를 거친다.

---

# 30. 최종 방향

Minecraft JARVIS는 단순 AI 채팅 플러그인이 아니다.

```text
Minecraft Server
      +
Server Observability
      +
Plugin / Mod Integration
      +
Natural Language Interface
      +
AI Tool Calling
      +
Controlled Operations
```

을 결합한 **플랫폼 독립형 Minecraft 서버 운영 AI**를 목표로 한다.

첫 번째 제품 목표는 복잡하게 잡지 않는다.

> 운영자가 Minecraft 채팅에서 JARVIS에게 자연어로 질문하면,
> GPT-6 Luna가 필요한 서버 Tool을 선택하고,
> Paper / Fabric / NeoForge Adapter가 실제 서버 데이터를 조회한 뒤,
> JARVIS가 그 결과를 자연스럽게 설명한다.

이 구조를 안정화한 뒤 CoreProtect 기반 사건 조사,
WorldGuard 보호구역 분석,
CMI 운영 기능,
승인형 서버 조치,
선제적 이상 감지 순으로 확장한다.

---

# 참고

- OpenAI GPT-6 Luna model ID: `gpt-6-luna`
- 권장 API: OpenAI Responses API
- GPT-6 Luna의 function calling / structured outputs를 Tool 시스템에 활용
- CoreProtect / WorldGuard / CMI 연동은 각각 별도의 Provider로 격리
- 플랫폼 공통 코드에는 Bukkit / Fabric / NeoForge 종속성을 두지 않음
