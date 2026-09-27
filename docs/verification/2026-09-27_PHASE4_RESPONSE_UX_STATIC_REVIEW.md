# Phase 4 response UX static review

작성일: 2026-09-27  
대상 브랜치: `codex/phase1-config-foundation`

## 구현 범위

- configurable response prefix
- legacy ampersand prefix formatting
- threshold 기반 progress/waiting message
- late-progress completion gate
- requester-only response sound
- Paper/Fabric/NeoForge 공통 `StyledChatMessage`

## Formatting boundary

지원 prefix code는 `&0..&9`, `&a..&f`, `&k..&o`, `&r`이다.
legacy parser는 configured prefix만 해석하고 Luna/model body는 plain segment로
유지한다. 따라서 model body 안의 `&c`, `&k` 등은 스타일 제어로 승격되지
않는다.

기본 prefix는 `[JARVIS] `로 유지한다. 예:
`&5[JARVIS]&r `.

## Progress flow

```text
Brain submit
  -> threshold timer
  -> 아직 처리 중이면 server scheduler로 progress 예약
  -> server thread에서 completion/auth/session 재확인
  -> 최대 1회 public progress

Brain completion
  -> progress.complete()
  -> final/error public response
```

waiting message는 request UUID hash 기반으로 deterministic하게 하나를 선택한다.

## Sound boundary

`response.sound.enabled=true`이면 최종 또는 안전한 error response 후 요청자에게만
sound를 재생한다. progress에는 sound를 재생하지 않는다. sound lookup/playback
실패는 chat response를 실패로 바꾸지 않는다.

## Thread boundary

timer delay는 Minecraft server thread를 block하지 않는다. 실제 styled message와
sound API 호출은 기존 `ServerScheduler`를 통해 platform safe context에서만
수행한다.

## 검증 상태

사용자 요청에 따라 테스트/빌드는 실행하지 않았다.

미검증:
- Java/Gradle compile
- Paper 1.21.8 styled component/sound runtime
- Fabric 1.21.8+build.1 registry/sound runtime
- NeoForge 21.8.52 registry/sound runtime
- progress timing/race
- prefix rendering parity
- invalid sound ID
- 기존 E8-E16 회귀
- clean-server boot

이 문서는 정적 검토 상태만 기록한다.
