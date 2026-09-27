# 나중에 할 것들

현재 Phase 1~8 및 Operational Logging L1~L6 구현 이후 남아 있는 항목만 기록한다.

## Admin command

- `/jm reload`
  - runtime config 재로드
  - parse/validation 실패 시 기존 valid snapshot 유지
  - 성공/실패 operational log
- `/jm test`
  - provider/Jev/Luna/Tool boundary를 안전하게 확인할 수 있는 진단 명령
  - secret/raw prompt는 출력하지 않음

## Verification 보강

- protocol fixture Java verification을 정식 task로 묶기
- scheduling/ACTIVE race deterministic coverage 보강
- operational logging event/secret masking regression coverage 추가
- Audit health degraded/recovered injection test 추가

## 운영성

- 필요 시 metrics exporter 검토
  - request latency/count
  - Jev/Luna latency
  - Tool latency/error count
  - AI queue depth
  - Audit queue depth
  - proactive candidate/accept count
- 반복 warning에 대한 rate limiting 필요성 검토
