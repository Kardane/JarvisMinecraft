# ADR-0004: Paper External Provider Reuse Boundary

- Status: Accepted
- Date: 2026-09-24
- Related work: T00

## Context

Target Paper servers may run CoreProtect, WorldGuard, and CMI. Reimplementing those capabilities inside JARVIS, or depending on plugin-internal databases, command output, or non-public internals, would reduce maintainability and safety.

## Decision

Use **public APIs only** for external Providers and treat them as optional capabilities. Plugin presence alone must not enable a capability.

### CoreProtect

- Initial compile target: CoreProtect 24.0 / API v12
- First runtime target: 24.1
- v0.1.1: query features only
- Capability requirements: verified plugin type + API enabled + APIVersion >= 12
- No direct access to the internal database
- Rollback remains unsupported until the v0.2 approval/journal contract

### WorldGuard

- First runtime target: 7.0.18
- v0.1.1: region/flag/protection queries only
- Use public query APIs such as `RegionQuery.testState` for protection checks
- Do not assume RegionQuery automatically incorporates bypass; use SessionManager bypass evaluation separately
- Verify the WorldEdit companion dependency as part of compatibility checks

### CMI

- Not part of the v0.1/v0.1.1 Tool catalog
- Public API and license/redistribution conditions are verified separately in v0.2 T14
- Do not claim compatibility between CMI runtime 9.8.9.6 and the publicly documented CMI-API 9.8.6.4 combination until a real-server smoke test
- Do not bundle CMI/CMI-API binaries in JARVIS artifacts

### Shared Rules

- If a Provider is absent or its version/API-state verification fails, do not expose the related Tools to the AI.
- Do not shade third-party plugin binaries into JARVIS distributions by default.
- Do not pass Bukkit/plugin objects into the Brain or shared Java module.

## Consequences

Benefits:

- Core JARVIS features remain available on servers without these plugins.
- Plugin-version problems are isolated at the capability boundary.
- Reduces coupling to internal implementations and database schemas.

Costs:

- Features not exposed by public APIs must remain unsupported or be deferred.
- CMI features cannot be enabled before license/compatibility verification.

## Verification Status

The exact CoreProtect/WorldGuard/CMI runtime combination had not yet been smoke-tested when this decision was recorded. CoreProtect and WorldGuard integration is verified in T11/T12/T13; CMI is verified in T14.

## Official References

- CoreProtect API: https://docs.coreprotect.net/api/
- WorldGuard dependency: https://worldguard.enginehub.org/en/latest/developer/dependency/
- WorldGuard protection query: https://worldguard.enginehub.org/en/latest/developer/regions/protection-query/
- CMI API: https://www.zrips.net/cmi/api/
