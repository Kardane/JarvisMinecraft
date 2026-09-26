# Build and verification

갱신일: 2026-09-27

## 기준

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
- OpenAI Java SDK: 4.69.2
- Jev: TypeSafe HTTPS API through the Java classifier

E14 이후 production build/runtime에는 Node.js/npm이 필요하지 않는다.

## 전체 빌드

```bash
./gradlew build
```

CI도 동일한 Java/Gradle build를 실행하며, E16에서는 추가로 세 플랫폼 clean-server boot smoke를 실행한다.

## 배포 artifact

E16 이후 플랫폼별 배포 파일은 다음 이름으로 생성된다.

```text
minecraft/paper/build/libs/jarvisminecraft-paper.jar
minecraft/fabric/build/libs/jarvisminecraft-fabric.jar
minecraft/neoforge/build/libs/jarvisminecraft-neoforge.jar
```

각 artifact는 `minecraft/common`의 Embedded Brain과 공식 OpenAI Java SDK runtime을 포함한다. SDK 및 runtime dependency package는 `io.github.kardane.jarvisminecraft.internal.shaded` 아래로 relocation해 Minecraft/Paper/Fabric/NeoForge의 Jackson/OkHttp/Kotlin classpath와 분리한다. Minecraft가 제공하는 Gson은 artifact에 중복 포함하지 않는다.

루트 `verifyE16Artifacts` task가 필수 Embedded Brain class, relocated SDK class, unrelocated conflict package 부재, Node/JavaScript runtime asset 부재를 검사한다.

## 주요 deterministic verification

`minecraft/common:check`에는 Embedded Brain orchestration 검증과 E12 policy parity/safety verification이 포함된다.

플랫폼 모듈의 `check`는 각각 T06/T07/T08 계약 검증을 실행한다. Paper는 optional Provider T11~T14 검증도 포함한다.

## Live model verification

실제 Jev/Luna provider smoke는 credentials가 있을 때 명시적으로 실행한다.

```bash
OPENAI_API_KEY=... TYPESAFE_API_KEY=... \
  ./gradlew :minecraft:common:embeddedBrainLiveVerification
```

이 작업은 기본 CI에서 외부 provider를 호출하지 않는다.

## Dependency locking

Gradle subproject는 committed dependency lock을 사용한다.

- `minecraft/common/gradle.lockfile`
- `minecraft/paper/gradle.lockfile`
- `minecraft/fabric/gradle.lockfile`
- `minecraft/neoforge/gradle.lockfile`

과거 `brain/package-lock.json`은 E14에서 Node Brain package와 함께 제거되었다.

## Contract/evaluation assets

`protocol/schema`, `protocol/fixtures`, `evals`, `tests/acceptance/out`은 삭제하지 않는다. 다만 E14 이후 이 자산들은 별도 Node runtime의 실행 입력이 아니라 compatibility, regression, historical evidence 용도다.

`GeneratedContractConstants.java`는 schema/policy source와 함께 committed contract artifact로 유지한다. source 상수를 변경할 때 같은 change에서 Java 상수와 E12 fixture 기대값을 함께 검토한다.
