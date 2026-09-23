# T02 Build Skeleton

작성일: 2026-09-24  
상태: T02 build/CI 기준

## 고정 package 경로

공통 base package:

`io.github.kardane.jarvisminecraft`

담당 경로:

- T03 common: `io.github.kardane.jarvisminecraft.common`
- T06 Paper: `io.github.kardane.jarvisminecraft.paper`
- T07 Fabric: `io.github.kardane.jarvisminecraft.fabric`
- T08 NeoForge: `io.github.kardane.jarvisminecraft.neoforge`

플랫폼 구현은 다른 플랫폼 package를 import하지 않는다. 공통 모듈에는 Bukkit/Paper, Fabric, NeoForge, NMS import를 추가하지 않는다.

## 빌드 기준

- Java: 21
- Gradle Wrapper: 8.14.5
- Minecraft: 1.21.8
- Paper API: 1.21.8-R0.1-SNAPSHOT
- Fabric Loader: 0.17.2
- Fabric API: 0.133.4+1.21.8
- Yarn: 1.21.8+build.1
- Fabric Loom: 1.12.2
- NeoForge: 21.8.52
- ModDevGradle: 2.0.147
- Node: 24
- OpenAI SDK: 7.22.0
- TypeSafe SDK: 0.6.0

## 명령

Java 전체:

`./gradlew build`

Brain:

`cd brain && npm ci && npm run check && npm run build`

Protocol fixture 검증:

`cd brain && npm run test:protocol`

## Lock 정책

Gradle의 모든 subproject는 dependency locking을 켠다. npm은 `package-lock.json`을 사용한다.

T02 bootstrap CI가 첫 dependency resolution에서 lock 파일을 생성하며, T02 완료 전 생성물을 저장소에 커밋한다. 이후 CI는 lock 파일을 갱신하지 않고 그대로 소비한다.

루트 Gradle/build/CI 파일은 T02 병합 후 통합 담당만 수정한다. 다른 작업은 의존성 변경 요청을 인계한다.
