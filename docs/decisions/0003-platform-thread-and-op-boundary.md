# ADR-0003: 플랫폼 스레드 및 OP 권한 경계

- 상태: Accepted
- 일자: 2026-09-24
- 관련 작업: T00

## 맥락

Minecraft 이벤트는 플랫폼마다 스레드 의미가 다르며, AI/네트워크/DB 호출은 지연될 수 있다. 또한 Brain 또는 LLM이 전달하는 actor 정보는 서버 권한의 신뢰 근거가 될 수 없다.

## 결정

### OP 권한

JARVIS v0.1의 상호작용 주체는 **현재 접속 중이며 서버 자체가 OP로 인정하는 플레이어**로 제한한다.

권한 원천:

- Paper: 서버의 `isOp()`
- Fabric: `PlayerManager.isOperator(GameProfile)`
- NeoForge: vanilla operator list / PlayerList의 실제 operator 판정

`isOp=true` 같은 Brain payload, LLM 출력, 별도 `jarvis.*` permission node만으로 비OP를 허용하지 않는다.

권한은 다음 세 지점에서 재검사한다.

1. JARVIS 대화 접수 직전
2. Tool 실행 직전
3. 결과 전달 직전

deop 또는 logout이 확인되면 세션과 대기 변경을 폐기한다.

### 스레드

- AI HTTP, WebSocket, 디스크, DB I/O를 Minecraft tick/server thread에서 기다리지 않는다.
- 이벤트 콜백에서 얻은 플랫폼 객체를 장기 보관하거나 Brain으로 전달하지 않는다.
- 서버/월드/플레이어 접근은 플랫폼이 보장하는 server execution context에서 제한된 DTO snapshot으로 변환한다.
- Paper `AsyncChatEvent`는 비동기일 수 있으므로 Bukkit world API를 핸들러에서 직접 사용하지 않는다.
- Fabric 채팅 callback의 세부 thread 계약은 T07 실검증 전까지 추정하지 않는다.
- NeoForge의 정확한 1.21.8 event/mapping signature는 T02/T08 compile 결과로 고정한다.

## 결과

장점:

- 모델/Brain을 신뢰 경계 밖에 둔다.
- deop/logout race에서 stale 권한으로 Tool이 실행되는 위험을 줄인다.
- 느린 외부 I/O가 서버 tick을 직접 막는 구조를 방지한다.

비용:

- 모든 플랫폼 Adapter에 scheduler/execution bridge가 필요하다.
- 읽기 전용 Tool도 snapshot 경계를 고려해야 한다.

## 검증 상태

T00에서는 API 문서와 이벤트 의미를 조사했다. deop/logout race, 채팅 비공개 처리, 실제 callback thread는 T06~T10에서 서버/클라이언트로 검증한다.

## 공식 근거

- Paper chat events: https://docs.papermc.io/paper/dev/chat-events/
- Paper ServerOperator: https://jd.papermc.io/paper/1.21.8/org/bukkit/permissions/ServerOperator.html
- Fabric ServerMessageEvents: https://maven.fabricmc.net/docs/fabric-api-0.133.4%2B1.21.8/net/fabricmc/fabric/api/message/v1/ServerMessageEvents.html
- Fabric PlayerManager: https://maven.fabricmc.net/docs/yarn-1.21.8%2Bbuild.1/net/minecraft/server/PlayerManager.html
- NeoForge events: https://docs.neoforged.net/docs/1.21.8/concepts/events/
