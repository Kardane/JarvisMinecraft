# T11 CoreProtect history Provider

- Compile target: CoreProtect 24.0, API v12+, as `compileOnly`; CoreProtect is not bundled.
- Exposes handlers for `lookup_area_history` and `lookup_player_history`. Paper registry/capability assembly is owned by T13.
- Queries run on a bounded worker pool. Queue saturation returns `BUSY`; expired work returns `TIMEOUT`.
- API v12 returns the matching lookup rows without a page offset. The Provider pins each query result in a bounded, two-minute snapshot cache and serves opaque, actor/session/query-bound cursors from that snapshot. At most 4,096 records are retained per query; a capped response sets `truncated=true`.
- CoreProtect block history rows identify actors by name. Area results leave `actorUuid` null. Player history resolves names through CoreProtect username history and only includes names that map to one UUID in the requested lookback. Ambiguous or malformed names are omitted and reported with `truncated=true`; this can make a result incomplete.
- `T11VerificationMain` covers API presence/type/enabled/version gates, empty and partial results, paging, cursor binding, player UUID mapping, and timeout behavior. It uses a fake API boundary and does not claim a live CoreProtect server test.

References: [CoreProtect API v12](https://docs.coreprotect.net/api/version/v12/) and [CoreProtect API v13 lookup behavior](https://docs.coreprotect.net/api/version/v13/).
