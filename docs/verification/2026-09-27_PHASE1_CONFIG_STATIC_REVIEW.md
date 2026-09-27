# Phase 1 runtime config static review

작성일: 2026-09-27  
대상 브랜치: `codex/phase1-config-foundation`  
기준 커밋: `ddc753a5bcf2838cbed7206b86a1fc11fec815be`

## 범위

Phase 1은 기존 v0.1 동작을 바꾸지 않는 runtime configuration foundation만
대상으로 한다.

정적 검토 대상:

- 공통 immutable `JarvisConfig`
- `JarvisConfigLoader`와 typed source contract
- `ConfigManager` atomic/fail-safe reload
- Paper YAML source
- Fabric/NeoForge optional `jarvis.properties`
- non-secret status summary
- config verification test entrypoint
- 운영/architecture 문서

## 정적 검토 결과

- 기본 interaction vocabulary는 `PASSIVE`, audience 기본값은 `OP`다.
- 모델 이름은 `gpt-6-luna` 외 값을 validation에서 거부한다.
- execution actor는 Phase 1에서 `OP`만 허용한다.
- scheduling은 기본 비활성이고 최대 delay/duration 값은 60초 이하로 검증한다.
- provider API key와 server identity는 `JarvisConfig`에 포함하지 않는다.
- Paper/Fabric/NeoForge bootstrap은 snapshot을 load/validate하지만 현재
  `ChatSessionManager`, Jev routing, Luna reasoning, Tool policy에는 전달하지 않는다.
- initial parse 실패는 startup 실패 경로로 들어가며, `ConfigManager.reload()`
  실패는 이전 snapshot을 유지한다.
- runtime summary는 provider secret이나 전체 config를 출력하지 않는다.
- `ADMIN`은 current Work Spec 미승인 범위이므로 config enum에 추가하지 않았다.

## 검증 상태

이번 작업에서는 저장소 지침에 따라 사용자가 별도로 요청하지 않은 빌드,
테스트, 서버 기동, 외부 모델 호출을 실행하지 않았다.

따라서 다음은 **미검증** 상태다.

- Java/Gradle compile
- `jarvisConfigVerification`
- 전체 `./gradlew build`
- Paper/Fabric/NeoForge clean boot
- 실제 config reload command (Phase 1에서는 command 자체가 아직 없음)

실행 검증을 수행하기 전까지 이 문서는 정적 검토 증거로만 사용한다.
