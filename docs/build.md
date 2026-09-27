# Build and Verification

Last updated: 2026-09-27

## Baseline

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

Production builds and runtime no longer require Node.js/npm after E14.

## Full Build

```bash
./gradlew build
```

CI runs the same Java/Gradle build. Since E16, it also runs clean-server boot smoke tests for all three platforms.

## Deployment Artifacts

Since E16, platform-specific deployment files are generated with these names:

```text
minecraft/paper/build/libs/jarvisminecraft-paper.jar
minecraft/fabric/build/libs/jarvisminecraft-fabric.jar
minecraft/neoforge/build/libs/jarvisminecraft-neoforge.jar
```

Each artifact includes the Embedded Brain from `minecraft/common` and the official OpenAI Java SDK runtime. SDK and runtime dependency packages are relocated under `io.github.kardane.jarvisminecraft.internal.shaded` to isolate them from Minecraft/Paper/Fabric/NeoForge Jackson, OkHttp, and Kotlin classpaths. Gson provided by Minecraft is not bundled again.

The root `verifyE16Artifacts` task checks required Embedded Brain classes, relocated SDK classes, the absence of unrelocated conflicting packages, and the absence of Node/JavaScript runtime assets.

## Primary Deterministic Verification

`minecraft/common:check` includes Embedded Brain orchestration verification and E12 policy parity/safety verification.

Each platform module's `check` task runs the T06/T07/T08 contract verification for that platform. Paper also includes optional Provider T11–T14 verification.

## Verification

The default gate is `./gradlew build`. Real Provider calls are not part of default CI.

Use [testing.md](testing.md) as the source of truth for individual deterministic tasks, clean-server boot tests, live-model checks, gameplay smoke tests, and release gates.

## Dependency Locking

Gradle subprojects use committed dependency locks.

- `minecraft/common/gradle.lockfile`
- `minecraft/paper/gradle.lockfile`
- `minecraft/fabric/gradle.lockfile`
- `minecraft/neoforge/gradle.lockfile`

The former `brain/package-lock.json` was removed with the Node Brain package in E14.

## Contract and Evaluation Assets

Do not delete `protocol/schema`, `protocol/fixtures`, `evals`, or `tests/acceptance/out`. After E14, these assets are used for compatibility, regression analysis, and historical evidence rather than as runtime input for a separate Node process.

`GeneratedContractConstants.java` remains a committed contract artifact alongside schema/policy sources. When source constants change, review the Java constants and E12 fixture expectations in the same change.
