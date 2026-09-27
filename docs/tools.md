# Minecraft JARVIS Tool Contract

Date: 2026-09-24  
Related work: T01  
Canonical input/result schema: ../protocol/schema/protocol.schema.json

## 1. Principles

Tools are not an interface that gives the LLM unrestricted Minecraft server authority. They are a **narrow, pre-registered function allowlist**.

The initial release does not provide:

- arbitrary console commands
- SQL execution
- code execution
- arbitrary file access
- arbitrary-coordinate teleportation
- forced teleportation of another player
- ban / warn
- rollback

Even if the Brain invents an arbitrary Tool name, the Adapter does not execute it.

## 2. Catalog by Release

### v0.1

| Tool | Capability | Mutation | Risk |
|---|---|---:|---|
| get_server_status | server.status | read-only | READ_ONLY |
| get_online_players | player.list | read-only | READ_ONLY |
| get_player | player.lookup | read-only | READ_ONLY |
| get_player_location | player.location | read-only | READ_ONLY |
| get_nearby_players | player.nearby | read-only | READ_ONLY |
| get_world_info | world.info | read-only | READ_ONLY |
| teleport_staff | staff.self_teleport | mutation | LOW |

### Phase 6 structured actions

| Tool | Capability | Mutation | Risk |
|---|---|---:|---|
| weather_set | world.weather.set | mutation | LOW |
| time_set | world.time.set | mutation | LOW |

Neither Tool executes a raw console command. Each operates only on one currently loaded world
through direct platform APIs and must pass both the execution-policy allowlist and a fresh current-online-OP
check.

### v0.1.1

| Tool | Capability | Provider | Mutation |
|---|---|---|---:|
| lookup_area_history | history.lookup | CoreProtect | read-only |
| lookup_player_history | history.lookup | CoreProtect | read-only |
| get_regions_at_location | region.lookup | WorldGuard | read-only |
| get_region_info | region.lookup | WorldGuard | read-only |
| check_build_permission | region.protection | WorldGuard | read-only |
| get_cmi_player_info | player.cmi_profile | CMI | read-only |

`get_cmi_player_info` is the optional Paper Tool introduced in T14. It looks up exactly one currently online player by UUID and returns the CMI nickname and AFK state. Nickname color codes and control characters are removed, output is limited to 64 UTF-16 units, and the Minecraft name is used when no nickname exists. It does not query offline user data, play time, or CMI warnings. If the CMI API is unavailable or the call fails, it fails closed with `PROVIDER_UNAVAILABLE`.

Paper advertises the CMI capability and Tool only when both CMI and CMILib are active and the public API contract can be used. The user confirmed explicit permission from Zrips. Runtime smoke for CMI 9.8.9.6 + CMILib 1.5.9.9 is verified separately in the real server environment.

## 3. Common Results

Every Tool returns the wrapper below.

~~~json
{
  "status": "OK",
  "data": {},
  "error": null,
  "observedAt": "2026-09-23T15:10:02Z",
  "source": "Paper",
  "truncated": false
}
~~~

### status

- `OK`: execution succeeded and `data` contains a meaningful result.
- `EMPTY`: the query completed normally but the collection/history is empty.
- `ERROR`: request failed; `data=null` and `error` is required.
- `UNSUPPORTED`: the current platform/Provider does not provide the feature; `data=null` and `error` is required.

If a Provider fails after startup, remove its capability as quickly as possible. Existing requests fail explicitly with `PROVIDER_UNAVAILABLE` or `UNSUPPORTED`.

## 4. get_server_status

Input: empty object.

~~~json
{}
~~~

Returned metrics:

- tps
- mspt
- onlinePlayers
- loadedChunks
- memoryUsedBytes
- memoryMaxBytes

Each metric contains `value`, `unit`, `windowMs`, `observedAt`, and `source`.

If a platform cannot safely provide a metric, **do not invent `0`; return `value=null`**.

Do not scan the entire server or load new chunks merely to calculate metrics.

## 5. get_online_players

Input:

~~~json
{
  "cursor": null,
  "limit": 50
}
~~~

Constraints:

- limit 1~100
- return at most 100 players
- return only UUID + current name
- do not return IP address, connection address, or private chat
- provide `nextCursor` if another page exists
- do not guess the total count when it is not known exactly

Zero online players is a normal empty list, not an error.

## 6. get_player

Use exactly one of the following selectors.

~~~json
{"playerUuid": "uuid"}
~~~

Or:

~~~json
{"exactName": "Steve"}
~~~

v0.1 does not perform prefix/fuzzy search. Return `AMBIGUOUS_TARGET` if the request cannot resolve to one UUID and `NOT_FOUND` if the target does not exist.

Returns:

- player.uuid
- player.name
- online=true

Bulk offline-profile search is outside the initial scope.

## 7. get_player_location

Input:

~~~json
{"playerUuid": "uuid"}
~~~

Constraints:

- target must currently be online
- read from currently loaded server/world state
- return `worldId`, x/y/z, yaw/pitch, and `observedAt`
- return `NOT_FOUND` if offline
- do not let the model infer or fill in coordinates

## 8. get_nearby_players

Input:

~~~json
{
  "center": {
    "worldId": "world",
    "x": 0,
    "y": 64,
    "z": 0,
    "yaw": 0,
    "pitch": 0
  },
  "radius": 32,
  "limit": 50
}
~~~

Constraints:

- `radius > 0`, maximum 64 blocks
- `limit` maximum 100
- same world only
- use Euclidean distance consistently in the platform implementation
- do not load new chunks for the query
- each returned array element includes player and distance

## 9. get_world_info

Input:

~~~json
{"worldId": "world"}
~~~

Common returnable information:

- worldId
- dimensionKey
- playerCount
- difficulty
- timeOfDay

Values a platform cannot safely provide are `null`. Return `NOT_FOUND` if the world itself does not exist.

## 10. teleport_staff

Input:

~~~json
{"targetPlayerUuid": "uuid"}
~~~

Top-level `actionId` is required.

### Absolute Rules

- the actor being moved is always `requesterUuid`
- arguments never contain the requester UUID
- re-check that `targetPlayerUuid` is online and has a current location immediately before execution
- re-check that the requester is online + OP immediately before execution
- arbitrary-coordinate movement is unsupported
- do not move another player
- do not call this Tool for a simple location question such as "Where is Steve?"
- the Brain may propose it only on a turn where the user explicitly asks to move themselves to the target
- do not expose it in Jev failure fallback

If the platform teleport API cancels or fails, do not manufacture success. Return `completed=true` only after actual completion is confirmed.

If timeout/ACK loss makes the result uncertain, return `OUTCOME_UNKNOWN` and do not automatically re-execute.

## 10.1 weather_set — Phase 6

Input:

~~~json
{
  "worldId": "world",
  "weather": "CLEAR",
  "durationSeconds": 600
}
~~~

Constraints:

- `worldId` must refer to a currently loaded world
- `weather` must be one of `CLEAR / RAIN / THUNDER`
- `durationSeconds` must be 1–3600
- requester must still be an online OP immediately before execution
- propose only for an explicit weather-change request
- implementation uses the platform weather API directly and never constructs a command string
- successful result includes `worldId`, applied `weather`, `durationSeconds`, and `completed=true`

## 10.2 time_set — Phase 6

Input:

~~~json
{
  "worldId": "world",
  "timeOfDay": 6000
}
~~~

Constraints:

- `worldId` must refer to a currently loaded world
- `timeOfDay` must be 0–23999
- preserve the current day count and change only that world's time-of-day
- requester must still be an online OP immediately before execution
- propose only for an explicit time-change request
- implementation uses the platform world-time API directly and never constructs a command string
- successful result includes `worldId`, the actually applied `timeOfDay`, and `completed=true`

## 11. lookup_area_history — v0.1.1

Provider: CoreProtect.

Input:

~~~json
{
  "center": {
    "worldId": "world",
    "x": 100,
    "y": 64,
    "z": 100,
    "yaw": 0,
    "pitch": 0
  },
  "radius": 10,
  "lookbackSeconds": 1800,
  "cursor": null,
  "limit": 100
}
~~~

Constraints:

- default UX range: radius 10 / last 30 minutes
- radius: 0~64
- lookbackSeconds: 1~86400
- limit: 1~100
- `radius=0` may be used for an exact-block-position query

When the Brain interprets phrases such as "recently," it may propose defaults, but **the Adapter fixes the actual `windowStart/windowEnd` relative to execution time**.

Returns:

- at most 100 `records`
- returnedCount
- `totalCount`: `null` if the backend does not provide an exact value
- nextCursor
- windowStart
- windowEnd

`truncated=true` does not mean there were no results.

CoreProtect records establish only that a change was recorded under a given actor name. JARVIS must not state intent, malice, or griefing as fact.

## 12. lookup_player_history — v0.1.1

Input:

~~~json
{
  "playerUuid": "uuid",
  "lookbackSeconds": 1800,
  "cursor": null,
  "limit": 100
}
~~~

Time/count limits are the same as area history.

If the Provider cannot guarantee UUID-based lookup directly and must depend on name mapping, document that limitation in source/error/operations documentation. Do not infer that different users are the same actor and merge their records.

## 13. get_regions_at_location — v0.1.1

Provider: WorldGuard.

Input:

~~~json
{"location": {"worldId":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}}
~~~

Returned Region summary:

- id
- priority
- owners
- members

Region containment and priority follow WorldGuard API results. JARVIS does not implement its own region engine.

## 14. get_region_info — v0.1.1

Input:

~~~json
{
  "worldId": "world",
  "regionId": "spawn"
}
~~~

Returns:

- id
- worldId
- priority
- parentId
- owners
- members
- serializable flags

Convert flag values to simple string/number/boolean/null DTOs before sending them to the model. Never place Bukkit/WorldGuard objects themselves in the protocol.

## 15. check_build_permission — v0.1.1

Input:

~~~json
{
  "playerUuid": "uuid",
  "location": {
    "worldId": "world",
    "x": 0,
    "y": 64,
    "z": 0,
    "yaw": 0,
    "pitch": 0
  }
}
~~~

Returned decision:

- ALLOW
- DENY
- UNDEFINED

Additional returned data:

- bypass
- matchedRegions
- reason

JARVIS does not reimplement protection decisions by combining owner/priority/flags. Use WorldGuard `RegionQuery` and incorporate bypass through the separate official API decision.

## 16. Capability and Tool Exposure

The Brain exposes to the model only Tools present in `capabilities.tools` for that server connection.

Examples:

- Fabric without a CoreProtect Provider -> expose zero history Tools
- Paper without WorldGuard -> expose zero region Tools
- Provider version mismatch -> expose zero Tools even if a plugin file exists
- Provider runtime failure -> remove the capability and stop exposing it to new requests

The Adapter **re-checks capability at execution time** even if the Brain already knows the Tool.

## 17. Argument Validation

All arguments use strict schemas.

- reject unknown fields
- no automatic string -> number coercion
- validate UUID/date-time formats
- do not silently clamp values above limits; return `INVALID_ARGUMENT`
- provide no path that interpolates server object names directly into commands/SQL/code

LLM-generated values are not trusted input.

## 18. Data Minimization

Tool results contain only data necessary to answer the question.

Excluded by default:

- IP
- API key
- shared secret
- full server logs
- private chat
- authentication tokens
- filesystem path
- stack trace

Send player name, UUID, current location, and region/history information to external models only within the scope necessary to answer the relevant OP request.

## 19. T03/T04/T05 Handoff

T03:

- Keep Tool arguments/result DTOs 1:1 with the schema.
- Implement Adapter-side allowlist, capability checks, thread bridge, and result `source/observedAt`.

T04:

- Build the Tool registry by capability.
- Allow at most 8 Tool calls per request.
- Exclude state-changing Tools from fallback routes.
- Validate requester/session/server binding.

T05:

- Generate Luna function schemas from this catalog.
- If the model produces arguments outside the schema, ask again or return `INVALID_ARGUMENT`.
- Jev category narrows Tool candidates only; it does not grant execution authority.


## Phase 7 scheduling controls

### schedule_action

Schedulable nested Tools:

- `teleport_staff`
- `weather_set`
- `time_set`

Input contains nested `tool`, strict `arguments`, `delaySeconds`,
`intervalSeconds`, and `durationSeconds`.

- delay: 1–60 seconds
- one-shot: both interval and duration are null
- repeat: interval and duration are integers and `duration >= interval`
- duration: maximum 60 seconds
- if `scheduling.enabled=false`, do not expose the Tool itself
- do not register from a proactive origin

The registration result returns `scheduleId`, first execution time, and optional expiration time.
Actual execution does not reuse the registration request deadline.

### cancel_scheduled_action

Accept one `scheduleId` and cancel only a pending schedule owned by the current requester.
Return `NOT_FOUND` for another player's ID, an already-finished ID, or a nonexistent ID.
