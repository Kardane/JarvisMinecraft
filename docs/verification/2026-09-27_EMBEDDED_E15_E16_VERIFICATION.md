# Embedded Brain E15-E16 verification — 2026-09-27

## Scope

This verification closes the configuration and packaging phases of the Embedded Brain migration.

- E15: configuration simplification and stable local server identity
- E16: single-artifact packaging, SDK relocation, artifact inspection, and clean-server boot

## E15 result

Only two provider credentials are required for normal startup:

```text
OPENAI_API_KEY
TYPESAFE_API_KEY
```

`JARVIS_SERVER_ID` / `jarvis.serverId` is optional. Without an override, `ServerIdentity` creates a `local-<uuid>` identifier once under the platform data directory and reuses the persisted value across restarts.

Persistence locations:

```text
Paper:             plugins/JarvisMinecraft/server-id.txt
Fabric / NeoForge: config/jarvisminecraft/server-id.txt
```

Audit directories remain platform-local defaults:

```text
Paper:             plugins/JarvisMinecraft/audit
Fabric / NeoForge: config/jarvisminecraft/audit
```

The Embedded Brain verification covers generated-ID persistence, reload stability, explicit override handling, derived audit path, blank provider-key rejection, and invalid override rejection.

## E16 packaging result

Deployable outputs:

```text
minecraft/paper/build/libs/jarvisminecraft-paper.jar
minecraft/fabric/build/libs/jarvisminecraft-fabric.jar
minecraft/neoforge/build/libs/jarvisminecraft-neoforge.jar
```

Each artifact contains:

- platform Adapter entrypoint
- `minecraft/common`
- `EmbeddedBrain`
- Jev JDK HTTP classifier
- Luna/OpenAI Java SDK client
- audit implementation
- `CommonRuntime` Tool execution boundary

The official OpenAI Java SDK runtime is shaded into the common payload. Dependency packages are relocated below:

```text
io.github.kardane.jarvisminecraft.internal.shaded
```

This isolates the embedded SDK's Jackson, OkHttp/Okio, Kotlin and other runtime classes from the server/mod-loader classpath. Gson remains compile-only because Minecraft 1.21.8 provides Gson; the packaged artifacts are checked to contain no bundled `com/google/gson` classes.

## Artifact verification

The root `verifyE16Artifacts` Gradle task verifies all three final artifacts and fails when:

- a required Embedded Brain/runtime class is missing
- relocated OpenAI/Jackson/OkHttp classes are missing
- unrelocated OpenAI/Jackson/OkHttp/Okio/Kotlin classes are present
- bundled Gson classes are present
- Node/JavaScript runtime assets are present

This task is part of the root `check` / `build` lifecycle.

## Clean-server boot verification

CI runs a matrix for:

```text
paper
fabric
neoforge
```

Each matrix job:

1. builds the final deployable artifact;
2. creates a clean server directory;
3. installs/downloads only the platform runtime required by that server;
4. starts the server without OpenAI or TypeSafe credentials;
5. reaches the JARVIS platform entrypoint;
6. constructs and closes `OpenAiLunaClient` with a non-secret smoke key, exercising the relocated OpenAI/Jackson/OkHttp/Kotlin linkage;
7. writes the E16 boot marker;
8. shuts the server down cleanly.

The smoke-only branch executes before production credential validation. Normal JARVIS startup still requires both provider keys.

## CI evidence

### Run #250

Commit: `90715317e61c900a6661f5c60d16212644351591`

Result:

- Java / Gradle: success
- E16 clean boot / Paper: success
- E16 clean boot / Fabric: success
- E16 clean boot / NeoForge: success

This run verified the shaded artifacts and clean platform boot.

### Run #251

Commit: `f47ec505915d34ff0007d0574f461b4ad80c0993`

Result:

- Java / Gradle: success
- E16 clean boot / Paper: success
- E16 clean boot / Fabric: success
- E16 clean boot / NeoForge: success

Run #251 additionally exercised actual construction/closure of the relocated OpenAI SDK client inside each clean server runtime.

## Migration state after E16

Production deployment no longer requires:

```text
Node.js
npm
Brain daemon
WebSocket listener
shared secret
Brain URL/host/port
reconnect configuration
separate SDK JARs
```

A server operator installs one platform artifact and supplies the two provider credentials. E17 remains the final live gameplay E2E phase.
