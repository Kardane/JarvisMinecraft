# ADR-0001: Minecraft 1.21.8 Build Baseline

- Status: Accepted
- Date: 2026-09-24
- Related work: T00

## Context

Minecraft JARVIS must support Paper, Fabric, and NeoForge under one shared contract. If each platform independently tracks its newest version, the shared Java version, mappings, build plugins, and event APIs can easily diverge.

The work specification pins Minecraft 1.21.8 as the first official verification target. "1.21.8 or later" does not imply a binary-compatibility guarantee for later Minecraft versions.

## Decision

Pin the first G0/v0.1 verification baseline as follows:

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
- Folia: excluded from the initial support scope

If dependency resolution or compilation fails in T02, do not arbitrarily bump multiple versions. Record the failure evidence, make only the smallest compatibility adjustment, and update this ADR and the compatibility documentation together.

## Consequences

Benefits:

- Establishes a clear first E2E verification baseline across all three platforms.
- Keeps the T01 DTO/protocol contract and T02 build skeleton on the same runtime assumptions.
- Prevents unsupported claims of "latest Minecraft" compatibility.

Costs:

- New Minecraft versions cannot be added to the support list without separate compatibility verification.
- Platform release cadence may diverge as Fabric/NeoForge mappings and APIs change.

## Verification Status

T00 verified version availability through official documentation and distribution repositories. The actual Gradle multiproject build and dedicated-server startup were not yet run at that stage; those are verified in T02/T10.

## Official References

- Paper 1.21.8 API: https://jd.papermc.io/paper/1.21.8/
- Fabric Maven: https://maven.fabricmc.net/
- NeoForge 1.21.8 getting started: https://docs.neoforged.net/docs/1.21.8/gettingstarted/
- NeoForge Maven: https://maven.neoforged.net/
- Gradle releases: https://gradle.org/releases/
