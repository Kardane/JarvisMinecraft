# ADR-0001: Minecraft 1.21.8 빌드 기준선

- 상태: Accepted
- 일자: 2026-09-24
- 관련 작업: T00

## 맥락

Minecraft JARVIS는 Paper, Fabric, NeoForge 세 플랫폼을 하나의 공통 계약으로 지원해야 한다. 각 플랫폼의 최신 버전을 독립적으로 따라가면 공통 Java 버전, 매핑, 빌드 플러그인과 이벤트 API가 쉽게 어긋난다.

작업명세서는 첫 공식 검증 대상을 Minecraft 1.21.8로 고정한다. "1.21.8 이상"은 이후 Minecraft 버전에 대한 바이너리 호환 보장을 의미하지 않는다.

## 결정

첫 G0/v0.1 검증 기준을 다음과 같이 고정한다.

- Minecraft: 1.21.8
- Java toolchain: 21
- Gradle Wrapper: 8.14.5
- Paper API: 1.21.8-R0.1-SNAPSHOT
- Fabric Loader: 0.17.2
- Fabric API: 0.133.4+1.21.8
- Yarn: 1.21.8+build.1
- Fabric Loom: 1.12.2
- NeoForge: 21.8.52
- ModDevGradle: 2.0.147
- Folia: 초기 지원 범위에서 제외

T02에서 실제 dependency resolution 또는 compile이 실패할 경우 임의로 여러 버전을 올리지 않는다. 실패 근거를 남기고 가장 작은 호환 버전 조정만 수행하며 이 ADR과 compatibility 문서를 함께 갱신한다.

## 결과

장점:

- 세 플랫폼의 첫 E2E 검증 기준이 명확해진다.
- T01의 DTO/프로토콜 계약과 T02의 빌드 골격이 동일한 런타임 가정을 사용한다.
- "최신 Minecraft 지원"을 근거 없이 선언하는 일을 방지한다.

비용:

- 새 Minecraft 버전은 별도 호환성 검증 없이 지원 목록에 추가할 수 없다.
- Fabric/NeoForge 매핑/API 변경에 따라 플랫폼별 release cadence가 달라질 수 있다.

## 검증 상태

T00에서는 공식 문서/배포 저장소의 버전 존재를 조사했다. 실제 Gradle 멀티프로젝트 build와 각 dedicated server 기동은 아직 수행하지 않았으며 T02/T10에서 검증한다.

## 공식 근거

- Paper 1.21.8 API: https://jd.papermc.io/paper/1.21.8/
- Fabric Maven: https://maven.fabricmc.net/
- NeoForge 1.21.8 시작 문서: https://docs.neoforged.net/docs/1.21.8/gettingstarted/
- NeoForge Maven: https://maven.neoforged.net/
- Gradle releases: https://gradle.org/releases/
