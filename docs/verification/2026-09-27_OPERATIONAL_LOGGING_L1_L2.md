# Operational Logging L1/L2 verification

날짜: 2026-09-27  
기준: `main@0e7761966dfd99dfe4d7d373cd5c6873618f38c6`

## 구현 범위

- Phase L1
  - common `JarvisLog`, level/event/field helper, sanitizer, NoOp/configured wrapper
  - Paper/Fabric/NeoForge platform logger adapter
  - `jarvis.logging.*` runtime configuration
- Phase L2
  - `request.accepted/completed/failed`
  - `jev.completed/failed/fallback`
  - `routing.resolved`
  - `reasoning.resolved`
  - `luna.round_completed/failed`

Operational logging은 raw player chat, raw prompt/response를 event field로 전달하지 않는다. 기존 JSONL Audit 및 mutation fail-closed 경로는 변경하지 않았다.

## 수행한 확인

사용자 요청에 따라 build 확인만 수행했다.

- GitHub Actions run: `36314529661`
- 검증 commit: `35e726d1781a9c168eb53a2f9316717c81850a29`
- 명령: `./gradlew build --stacktrace`
- 결과: **PASS**

## 수행하지 않은 확인

다음은 실행하지 않았다.

- clean-server boot smoke
- Paper/Fabric/NeoForge 수동 시나리오
- live Jev/OpenAI provider 호출
- 별도 operational logging acceptance 시나리오
