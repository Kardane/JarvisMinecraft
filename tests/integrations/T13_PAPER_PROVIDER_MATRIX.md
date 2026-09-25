# T13 Paper Provider integration matrix

`IntegrationRegistry` keeps the built-in Paper Tools available and registers optional Tools only after their required plugins are enabled and the corresponding public API bridge initializes.

| CoreProtect | WorldGuard | WorldEdit | Expected optional Tools |
|---|---|---|---|
| absent | absent | absent | none |
| enabled with API v12+ | absent | absent | `lookup_area_history`, `lookup_player_history` |
| absent | enabled | absent | none; WorldGuard requires WorldEdit |
| absent | enabled | enabled | `get_regions_at_location`, `get_region_info`, `check_build_permission` |
| enabled with API v12+ | enabled | enabled | all five history and region Tools |
| disabled or API unavailable | any | any | CoreProtect Tools absent |

The automated T13 verification uses injected provider modules to exercise each installation combination, API-linkage failure, registration collision, and shutdown. Each module stages its Tools and commits them atomically, so an initialization or registration failure cannot leave a partial catalog behind. The actual plugin-backed lookup smoke still requires a Paper 1.21.8 server with compatible CoreProtect, WorldGuard, and WorldEdit jars; no third-party plugin jars are present in this checkout. Capture the server/plugin versions and one returned history record, region detail, and build decision before marking the live portion complete.

Run the local registry contract:

```powershell
.\gradlew.bat :minecraft:paper:t13Verification
```

For live verification, install the packaged JARVIS Paper adapter plus the desired plugin combination in a disposable Paper server, connect an OP test player, create one block change while CoreProtect is enabled, define one WorldGuard region, and issue the existing read-only Tools through the production Brain transport. Do not run warning or rollback operations.
