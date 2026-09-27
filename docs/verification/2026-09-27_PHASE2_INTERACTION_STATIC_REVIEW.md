# Phase 2 interaction policy static review

작성일: 2026-09-27  
대상 브랜치: `codex/phase1-config-foundation`

## 구현 범위

Phase 2에서 다음 interaction 정책을 production path에 연결했다.

- configurable wake words
- configurable follow-up TTL
- `OP / WHITELIST / ALL / BLACKLIST` audience
- `InteractionCoordinator`
- `AudiencePolicy`
- `InvocationMatcher`
- platform current-player identity lookup
- audience 변경/로그아웃에 따른 session invalidation
- reply 직전 interaction authorization 재확인

## 안전 경계

interaction audience와 Tool authority는 분리되어 있다.

- OP requester: 기존 active Tool routing 사용 가능
- non-OP requester accepted by audience: `toolsAllowed=false`
- `EmbeddedBrain`은 해당 request에 대해 active Tool set을 빈 집합으로 사용
- `CommonRuntime`의 current online OP authority 검사는 변경하지 않음
- raw command/file execution 경로는 추가하지 않음

따라서 `ALL` 또는 비OP whitelist를 사용해도 이 Phase에서 Minecraft Tool
권한이 비OP에게 확대되지는 않는다.

## Interaction semantics

- 호출어는 메시지 시작에서만 일치한다.
- 호출어 다음에는 끝 또는 공백/구두점 경계가 필요하다.
- 직접 호출은 새 session을 만든다.
- 활성 session의 일반 메시지는 follow-up으로 처리하고 TTL을 갱신한다.
- `대화 끝`은 local 종료다.
- `!내용`은 해당 메시지만 Brain을 우회한다.
- whitelist/blacklist 이름 비교는 대소문자를 무시한다.
- 기본 audience는 `OP`, 기본 TTL은 120초다.
- `ACTIVE`는 아직 proactive ambient-chat initiation을 수행하지 않는다.

## 호환성

기존 `ChatSessionManager.accept(...)`, 기존 chat-controller 생성자,
기존 `EmbeddedBrainGateway` 생성자/live overload는 기본 OP-only semantics로
호환 경로를 남겼다. Production bootstrap은 새 shared
`InteractionCoordinator`를 사용한다.

기존 `CancelReason.OP_REVOKED`는 protocol 호환을 위해 audience authorization
철회에도 내부 취소 사유로 재사용한다.

## 검증 상태

사용자 요청에 따라 이번 Phase에서는 테스트와 빌드를 실행하지 않았다.

따라서 다음은 아직 미검증이다.

- Java/Gradle compile
- 기존 Embedded Brain verification 회귀
- Paper/Fabric/NeoForge platform compile
- 실제 서버에서 audience mode 전환
- 실제 비OP Luna 대화
- session TTL/wake-word edge case
- clean-server boot

이 문서는 정적 코드 검토 상태만 기록한다.
