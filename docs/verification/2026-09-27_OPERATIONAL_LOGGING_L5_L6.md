# Operational Logging L5/L6 verification

날짜: 2026-09-27  
선행 구현: Operational Logging L1~L4

## 구현 범위

### Phase L5 — ACTIVE proactive

- `proactive.candidate`
- `proactive.accepted`
- `proactive.ignored`
- `proactive.failed`
- raw ambient chat는 로그하지 않고 requester UUID, context message count, confidence/threshold, reason code만 기록
- ignored는 DEBUG, accepted는 INFO, failure는 WARN

### Phase L6 — Audit health + status

- `audit.degraded`
- `audit.unhealthy`
- `audit.recovered`
- `jarvis.logging.health.interval-seconds` 기준 polling
- 동일 health 상태/동일 error code는 반복 출력하지 않음
- `BrainGateway.StatusSnapshot`으로 runtime/config/AI scheduler/proactive/Audit health를 조회
- Paper/Fabric/NeoForge 모두 OP 권한 `/jm status` 제공
- status 출력에는 secret/raw prompt/raw chat을 포함하지 않음

기존 state-changing Tool의 pre-execution Audit fail-closed semantics는 변경하지 않았다.

## 수행한 확인

사용자 요청에 따라 build 확인만 수행했다.

- GitHub Actions run: `36315953780`
- 검증 commit: `26a6acccfb682406ebcd0bfa283ebdf346d2a58e`
- 명령: `./gradlew build --stacktrace`
- 결과: **PASS**

## 수행하지 않은 확인

- clean-server boot smoke
- Paper/Fabric/NeoForge 수동 명령 실행
- ACTIVE proactive 실제 서버 시나리오
- Audit health 장애/회복 주입
- live Jev/OpenAI provider 호출
