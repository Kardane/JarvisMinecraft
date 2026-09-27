# Phase 1–8 Test Run Snapshot

Date: 2026-09-27
Target branch: `codex/phase1-config-foundation`
Commit: `a59184e5c2a7627190164cfb35fbebd6cac3d824`

## Environment

- Windows 11
- JDK 21.0.3 (`C:\Program Files\Java\jdk-21`)
- Gradle Wrapper 8.14.5
- Minecraft target: 1.21.8

The checkout remained on `main` because the target branch ref and `HEAD` both resolved to the same commit. No branch switch was made while the worktree contained local changes.

## Compile Results

- `:minecraft:common:compileJava`: PASS
- `:minecraft:paper:compileJava`: PASS
- `:minecraft:fabric:compileJava`: initially failed because `MinecraftFabricPlatformAccess` lacked imports for `Identifier`, `Registries`, `SoundCategory`, `MutableText`, and `Style`. Those imports were added, and a rerun passed.
- `:minecraft:neoforge:compileJava`: PASS after adding a Windows-only lock-validation exception for `io.netty:netty-transport-native-epoll`. The committed lockfile remains unchanged so Linux CI continues to enforce the epoll version.

The four-module compile gate passed after the NeoForge lock handling fix. The Fabric import fix and NeoForge build-script change are uncommitted source changes in this worktree.

## Verification Results

### Gradle Verification and Build

- `:minecraft:common:jarvisConfigVerification`: PASS
- `:minecraft:common:embeddedBrainParityVerification`: PASS (`Embedded Brain E12 parity verification OK`)
- `:minecraft:common:embeddedBrainVerification`: PASS (`Embedded Brain E8-E10 verification OK`)
- `:minecraft:common:embeddedBrainLiveVerification`: PASS after correcting its test fixture and final-response waiter. The Jev `SERVER_QUERY`, `ACTION_REQUEST`, and `GENERAL` greeting scenarios reached the live Jev and Luna providers. The action scenario executed `get_player` and `teleport_staff` against the fake platform. The greeting scenario used `ReasoningLevel.NONE` and made no Tool call. Final response and audit assertions passed, including secret masking.
- Paper verification: `t06Verification`, `t11Verification`, `t12Verification`, `t13Verification`, and `t14Verification` all PASS.
- Fabric verification: `t07Verification` and `verifyNoClientImports` PASS. NeoForge `verifyNoClientImports` PASS.
- Common, Paper, and Fabric `test`/`build` tasks completed in the full build. Gradle emitted a non-failing warning that automatic test framework dependency loading is deprecated for Gradle 9.
- The first full build exposed the Windows/Linux lock-state mismatch for `io.netty:netty-transport-native-epoll`. NeoForge now ignores that single module in dependency-lock validation on Windows only. Linux CI keeps the committed lock entry and its strict validation. The final `gradlew.bat build --continue --stacktrace --console=plain` PASS reported 42 actionable tasks, 14 executed and 28 up-to-date. `verifyE16Artifacts`, `check`, and `build` passed; the dependency lockfile was not modified.
- NeoForge `t08Verification`, `verifyNoClientImports`, `jar`, and `verifyT08BootSmoke` all PASS. The T08 dedicated server loaded Minecraft 1.21.8, NeoForge 21.8.52, and JARVIS, reached `Done`, wrote its boot marker, and shut down after saving the test world.
- Packaged NeoForge E16 clean boot PASS. On MSYS/Cygwin, the smoke script downloads with Python's standard library, converts the marker path to Windows form, and launches NeoForge with its Windows argument file. Unix still uses `run.sh`. The packaged JAR produced the E16 marker and exited successfully.
- The guide's proposed `interactionPolicyVerification`, `reasoningPolicyVerification`, `responseUxVerification`, `executionPolicyVerification`, `structuredActionVerification`, `scheduledActionVerification`, `activeProactiveVerification`, and `protocolFixtureVerification` tasks are not registered in the current Gradle scripts. Scheduling E2E, ACTIVE/race checks, and protocol-fixture task checks therefore have no runnable task in this checkout.

The deterministic embedded-brain test fixture now explicitly selects `EXECUTE_LITE` and allows `teleport_staff`, so the pre-execution audit test reaches the intended audit path. The live verification now waits for Luna's final response rather than treating progress as completion, uses an `EXECUTE_LITE` fixture for the action scenario, and closes its wrapped Luna client after completion.

### Server Boots and Gameplay

- Paper E16 clean boot: PASS using the already available Paper `1.21.8-1-d8cb3f5` JAR. The clean-boot marker is present. The official download attempt was unavailable in this environment, so this does not verify the download path.
- Fabric E16 clean boot: PASS. The Fabric API and server loader were obtained, the clean-boot marker was produced, and the server shut down normally.
- Isolated Paper gameplay server: PASS for boot. A fresh world under `build/e16-gameplay/paper` reached `Done (32.277s)!` with the JARVIS plugin enabled, and the loopback client joined as `Karned`.
- In-game ordinary public chat in `PASSIVE` mode appeared normally and did not trigger JARVIS. Sending `자비스 안녕` started the interaction, but JARVIS returned `자비스 처리 중 내부 문제가 발생했습니다.` This is a gameplay E2E failure; the cause is unresolved. The server log confirms both chat messages reached Paper, but does not include the underlying exception.
- The configured purple `[JARVIS]` prefix and note-block response sound were observed. Requester-only sound delivery was not verified because only one client was connected. No in-game Tool action or mutation was verified. A later follow-up query accidentally contained a trailing `t`, so it is not acceptance evidence.
- The isolated server was stopped cleanly after the session. Fabric and NeoForge graphical-client gameplay were not tested.
- NeoForge T08 dedicated-server boot and packaged E16 clean boot: PASS. Both test servers shut down automatically after writing their expected markers.

### Live API and Configuration

The live verification resolved its provider credentials from `config/.env.local` and `config/.env.acceptance.local`; secret values were not printed or added to the report. The root `.env` file was absent. All three live scenarios and their audit checks passed. This confirms those provider calls, but does not override the separate failing Paper gameplay E2E result above.

## Worktree Preservation

Pre-existing local edits and deletions were left untouched. This run added the Fabric import fix, test-harness corrections, Windows-specific NeoForge lock handling, cross-platform NeoForge E16 download and launch handling, and this verification snapshot. The final full build and both NeoForge server boots passed. The pre-existing `docs/later-todo.md` contains trailing whitespace and remains outside this snapshot.
