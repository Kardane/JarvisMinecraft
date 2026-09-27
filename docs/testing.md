# JARVIS testing guide

Last updated: 2026-09-27

This document covers only the current testing baseline for the Embedded Brain architecture on `main`. Historical Phase/E-series verification records are not maintained here.

## 1. Default Build

Run with Java 21.

```bash
./gradlew build --stacktrace
```

This command is the default deterministic gate and includes common/platform verification plus artifact checks.

Platform deployment artifacts:

```text
minecraft/paper/build/libs/jarvisminecraft-paper.jar
minecraft/fabric/build/libs/jarvisminecraft-fabric.jar
minecraft/neoforge/build/libs/jarvisminecraft-neoforge.jar
```

## 2. Primary Deterministic Verification

Run individually when needed:

```bash
./gradlew :minecraft:common:jarvisConfigVerification --stacktrace
./gradlew :minecraft:common:embeddedBrainVerification --stacktrace
./gradlew :minecraft:common:embeddedBrainParityVerification --stacktrace

./gradlew :minecraft:paper:t06Verification --stacktrace
./gradlew :minecraft:fabric:t07Verification --stacktrace
./gradlew :minecraft:neoforge:t08Verification --stacktrace

./gradlew verifyE16Artifacts --stacktrace
```

Paper optional provider verification:

```bash
./gradlew \
  :minecraft:paper:t11Verification \
  :minecraft:paper:t12Verification \
  :minecraft:paper:t13Verification \
  :minecraft:paper:t14Verification \
  --stacktrace
```

## 3. Clean-server boot

```bash
./gradlew :minecraft:paper:jar --stacktrace
bash scripts/e16-boot-smoke.sh paper

./gradlew :minecraft:fabric:remapJar --stacktrace
bash scripts/e16-boot-smoke.sh fabric

./gradlew :minecraft:neoforge:jar --stacktrace
bash scripts/e16-boot-smoke.sh neoforge
```

NeoForge-only smoke:

```bash
./gradlew :minecraft:neoforge:verifyT08BootSmoke --stacktrace
```

## 4. Live AI verification

Real Provider calls are not part of default CI and may incur API cost.

```bash
OPENAI_API_KEY=... TYPESAFE_API_KEY=... \
  ./gradlew :minecraft:common:embeddedBrainLiveVerification --stacktrace
```

Verify:

- Jev engagement/route/reasoning parse
- deterministic fallback
- Luna Tool loop
- reasoning effort remains stable
- subsequent model round after Tool result
- provider timeout/error safe failure

## 5. Manual Gameplay Smoke

Use a separate server directory for each of Paper, Fabric, and NeoForge. Do not commit server binaries, worlds, logs, or secret files to the repository.

Recommended actors:

```text
Admin = online OP
Guest = non-OP
Other = optional observer
```

Core scenarios:

- DIRECT wake-word request and FOLLOW_UP
- `/jm status` output
- mutation Tools are not exposed in `READ_TALK`
- allowlisted `teleport_staff/weather_set/time_set` in `EXECUTE_LITE`
- a non-OP admitted under `audience=ALL` can converse but receives zero Minecraft Tools
- Tool/reply blocked across de-op/logout/session-end races
- read-only fallback after Jev failure
- Luna failure safe response
- mutation fails closed when Audit is unavailable
- state-changing timeout yields `OUTCOME_UNKNOWN` with no automatic retry
- ACTIVE proactive cooldown/in-flight limits
- hard block on mutation Tools for ACTIVE proactive requests
- scheduled actions re-check policy/authority
- pending schedules are cancelled on shutdown

## 6. Scheduling E2E

Example configuration:

```yaml
execution:
  mode: EXECUTE_LITE
  actors: OP
  lite:
    allow-tools:
      - teleport_staff
      - weather_set
      - time_set

scheduling:
  enabled: true
  max-delay-seconds: 60
  max-duration-seconds: 60
```

Verify:

- one-shot delay
- repeating interval/duration
- no overlap
- only the owner can cancel
- execution blocked if scheduling is disabled, policy is revoked, requester is de-opped, or requester logs out before execution
- repetition stops after ERROR/TIMEOUT/OUTCOME_UNKNOWN
- schedules are not restored after restart

## 7. ACTIVE E2E

The safe initial baseline is `READ_TALK` with scheduling disabled.

Verify:

- ordinary public chat remains visible as usual
- no response when Jev returns `IGNORE`
- create a session only when `START_CONVERSATION` passes the confidence threshold
- 1-second classification interval
- at most one proactive classifier in flight
- cooldown enforced
- drop activation if mode changes to PASSIVE, audience is revoked, or a direct session appears during classification
- proactive requests expose no mutation/scheduling-control Tools

## 8. Audit and Operational Logging Verification

Audit:

- mutation `PRE_EXECUTION` record exists
- post-result record exists
- `requestId/toolCallId/actionId` correlation
- every scheduled run gets a new `toolCallId/actionId`
- no Provider key, Authorization header, raw prompt, or raw chat

Operational log:

- request/Jev/Luna/Tool/schedule/proactive lifecycle events appear
- Audit health changes emit `audit.degraded/unhealthy/recovered`
- unchanged Audit health is not logged again on every poll
- `/jm status` shows runtime, interaction, execution, scheduling, AI queue, proactive in-flight, and Audit health

## 9. Release gate

Minimum pre-release gate:

- `./gradlew build` PASS
- clean boot PASS on all three platforms
- core gameplay smoke PASS on Paper/Fabric/NeoForge
- non-OP mutation blocking PASS
- proactive mutation blocking PASS
- scheduling reauthorization PASS
- no Audit secret leakage
- no shutdown/restart lifecycle anomalies
- when release-validation credentials are available, live Jev/Luna PASS
