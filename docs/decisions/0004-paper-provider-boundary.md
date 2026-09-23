# ADR-0004: Paper 외부 Provider 재사용 경계

- 상태: Accepted
- 일자: 2026-09-24
- 관련 작업: T00

## 맥락

사용 대상 Paper 서버에는 CoreProtect, WorldGuard, CMI가 존재할 수 있다. JARVIS가 이 기능을 자체 재구현하거나 플러그인의 내부 DB/커맨드 출력/비공개 internals에 의존하면 유지보수성과 안전성이 악화된다.

## 결정

외부 Provider는 **공개 API만** 사용하며 optional capability로 취급한다. 플러그인 존재만으로 capability를 활성화하지 않는다.

### CoreProtect

- 초기 compile target: CoreProtect 24.0 / API v12
- runtime 첫 대상: 24.1
- v0.1.1: 조회 기능만
- capability 조건: plugin 타입 확인 + API enabled + APIVersion >= 12
- 내부 DB 직접 조회 금지
- rollback은 v0.2 승인/저널 계약 전 미지원

### WorldGuard

- runtime 첫 대상: 7.0.18
- v0.1.1: region/flag/protection query만
- 보호 판정은 `RegionQuery.testState` 등 공개 query API를 사용
- bypass는 RegionQuery가 자동 반영한다고 가정하지 않고 SessionManager의 bypass 판정을 별도로 사용
- WorldEdit companion 의존성을 함께 점검

### CMI

- v0.1/v0.1.1 Tool catalog에 넣지 않음
- v0.2 T14에서 공개 API와 라이선스/재배포 조건을 별도 검증
- CMI runtime 9.8.9.6과 공개 문서의 CMI-API 9.8.6.4 조합은 실서버 smoke test 전까지 호환으로 단정하지 않음
- CMI/CMI-API binary를 JARVIS artifact에 번들하지 않음

### 공통

- Provider가 없거나 버전/API 상태 검증이 실패하면 관련 Tool을 AI에게 노출하지 않는다.
- 타사 plugin binary는 기본적으로 JARVIS 배포물에 shade하지 않는다.
- Bukkit/plugin 객체는 Brain이나 공통 Java 모듈로 전달하지 않는다.

## 결과

장점:

- 플러그인 미설치 서버에서도 공통 JARVIS 기능을 유지할 수 있다.
- plugin 버전 문제를 capability 수준에서 격리할 수 있다.
- 내부 구현/DB schema 변경에 대한 결합도를 낮춘다.

비용:

- 공개 API가 제공하지 않는 기능은 지원하지 않거나 후속 범위로 남겨야 한다.
- CMI는 라이선스/호환 검증 전까지 기능을 활성화할 수 없다.

## 검증 상태

CoreProtect/WorldGuard/CMI 실제 조합의 runtime smoke test는 아직 하지 않았다. CoreProtect와 WorldGuard의 실제 통합은 T11/T12/T13, CMI는 T14에서 검증한다.

## 공식 근거

- CoreProtect API: https://docs.coreprotect.net/api/
- WorldGuard dependency: https://worldguard.enginehub.org/en/latest/developer/dependency/
- WorldGuard protection query: https://worldguard.enginehub.org/en/latest/developer/regions/protection-query/
- CMI API: https://www.zrips.net/cmi/api/
