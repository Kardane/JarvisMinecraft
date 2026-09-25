# 2026-09-25 로컬 검증 스냅샷

> 이 문서는 **역사적 검증 스냅샷**이다. 현재 branch, 현재 `main`, 최신 release gate를 설명하지 않는다.
> 이후 구현 상태는 현재 소스와 더 최신의 `docs/verification/` 문서를 기준으로 판단한다.

## 당시 소스 상태

- GitHub `main`, 로컬 `main`, `origin/main`: `8df2b04` (T10 acceptance merge).
- 로컬 작업 트리에는 당시 원격 `main`에 없던 T11~T14 Paper Provider 작업이 포함돼 있었다.
- CoreProtect/WorldGuard/CMI contract/build 검증과 실제 third-party plugin runtime smoke는 서로 다른 증거로 취급했다.

## 당시 로컬 검증 결과

| 검증 | 결과 | 당시 증거 경계 |
|---|---|---|
| Brain `check` + AI routing | PASS | Node 24.19.0; typecheck, protocol fixtures 33/33 valid·10/10 invalid, server tests 6/6, CMI AI routing test 11/11 |
| Gradle 전체 `build` | PASS | Java 21; common/Paper/Fabric/NeoForge 빌드와 T03, T06–T08, T11–T14 verification 통과 |
| A09 Jev live evaluation | FAIL | 200건 실행. dev 138/144 (95.83%), holdout 51/56 (91.07%)로 당시 95% 목표 미달 |
| A10 Jev + GPT-6 Luna live loop | PASS | Jev `SERVER_QUERY`, Luna `get_server_status` 호출, fixture TPS 19.95 기반 후속 응답 확인. 실제 Minecraft 조회 결과는 아님 |
| A11 Paper 1.21.8 | PASS | 23/23 live checks; MSPT p95 변화 −1.90ms |
| A11 Fabric 1.21.8 | PASS | 23/23 live checks; MSPT p95 변화 −4.00ms |
| A11 NeoForge 21.8.52 / MC 1.21.8 | PASS | 23/23 live checks; MSPT p95 변화 −2.88ms |
| Paper `check` + JAR | PASS | T06, T11, T12, T13, T14 verification 통과; CMI API classes는 배포 JAR에 포함하지 않음 |
| T14 CMI runtime smoke | PENDING | Paper 1.21.8 + CMI 9.8.9.6 + CMILib 1.5.9.9 실제 조회 미실행 |

A11 측정은 당시 Windows 데스크톱에서 수행됐다. 세 플랫폼 로컬 시나리오는 통과했지만 T10 보고서가 요구한 dedicated fixed-load host 확인은 남아 있었으므로 성능 release gate를 완료로 간주하지 않았다.

## 관련 증거

- T10 원격 acceptance snapshot: [T10_V01_REPORT.md](T10_V01_REPORT.md)
- 당시 로컬 출력 경로: `tests/acceptance/out/`
- 이 문서의 수치는 2026-09-25 당시 상태만 설명하며 최신 결과로 재해석하지 않는다.
