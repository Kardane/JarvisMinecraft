# T12 WorldGuard region Provider

- Compile targets: WorldGuard 7.0.14 and WorldEdit 7.3.16 as `compileOnly`; neither plugin is bundled. The T00 compatibility note names WorldGuard 7.0.18, but resolving that artifact against this project's Java 21 target requires Java 25, so it cannot be used with the current Java/Minecraft baseline. T00 compatibility documentation should be reconciled with this tested target.
- Exposes handlers for `get_regions_at_location`, `get_region_info`, and `check_build_permission`. Paper registry/capability assembly is owned by T13.
- Discovery requires both enabled WorldGuard and enabled WorldEdit. A missing dependency leaves the Provider unavailable.
- Region membership, priority, parent, and flag data come from the WorldGuard API. Build permission uses `RegionQuery.queryValue(..., Flags.BUILD)` and separately checks WorldGuard bypass through `SessionManager.hasBypass`.
- An offline player has no evaluable WorldGuard bypass session; the Provider reports `UNDEFINED` with an explicit reason. World and region reads do not load chunks.
- `T12VerificationMain` covers optional WorldEdit availability, priority/parent/member/flag preservation, bypass behavior, empty/missing results, and fail-closed errors using a fake WorldGuard boundary. It does not claim a live Paper + WorldGuard + WorldEdit test.

References: [WorldGuard dependency guide](https://worldguard.enginehub.org/en/latest/developer/dependency/) and [WorldGuard protection queries](https://worldguard.enginehub.org/en/latest/developer/regions/protection-query/).
