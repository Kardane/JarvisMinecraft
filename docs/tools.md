# Minecraft JARVIS Tool 계약

작성일: 2026-09-24  
관련 작업: T01  
정규 입력/결과 schema: ../protocol/schema/protocol.schema.json

## 1. 원칙

Tool은 LLM에게 Minecraft 서버 전체 권한을 주는 인터페이스가 아니다. **사전에 등록된 좁은 함수 allowlist**다.

초기 릴리스에는 다음 기능이 존재하지 않는다.

- 임의 console command
- SQL 실행
- 코드 실행
- 임의 파일 접근
- 임의 좌표 텔레포트
- 다른 플레이어 강제 텔레포트
- ban / warn
- rollback

Brain이 임의 Tool 이름을 생성해도 Adapter는 실행하지 않는다.

## 2. 릴리스별 Catalog

### v0.1

| Tool | Capability | 변경 여부 | 위험도 |
|---|---|---:|---|
| get_server_status | server.status | 읽기 | READ_ONLY |
| get_online_players | player.list | 읽기 | READ_ONLY |
| get_player | player.lookup | 읽기 | READ_ONLY |
| get_player_location | player.location | 읽기 | READ_ONLY |
| get_nearby_players | player.nearby | 읽기 | READ_ONLY |
| get_world_info | world.info | 읽기 | READ_ONLY |
| teleport_staff | staff.self_teleport | 변경 | LOW |

### v0.1.1

| Tool | Capability | Provider | 변경 여부 |
|---|---|---|---:|
| lookup_area_history | history.lookup | CoreProtect | 읽기 |
| lookup_player_history | history.lookup | CoreProtect | 읽기 |
| get_regions_at_location | region.lookup | WorldGuard | 읽기 |
| get_region_info | region.lookup | WorldGuard | 읽기 |
| check_build_permission | region.protection | WorldGuard | 읽기 |

CMI Tool은 T14에서 공개 API/라이선스/runtime 조합을 검증하기 전 catalog에 추가하지 않는다.

## 3. 공통 결과

모든 Tool은 아래 wrapper를 반환한다.

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

- OK: 성공했고 data가 의미 있는 결과를 포함한다.
- EMPTY: 정상 조회했으나 컬렉션/이력이 비어 있다.
- ERROR: 요청 실패. data=null, error 필수.
- UNSUPPORTED: 현재 플랫폼/Provider가 기능을 제공하지 않는다. data=null, error 필수.

Provider가 시작 후 장애가 나면 capability를 가능한 빨리 제거한다. 이미 들어온 요청은 PROVIDER_UNAVAILABLE 또는 UNSUPPORTED로 명시적으로 실패한다.

## 4. get_server_status

입력: 빈 object.

~~~json
{}
~~~

반환 metric:

- tps
- mspt
- onlinePlayers
- loadedChunks
- memoryUsedBytes
- memoryMaxBytes

각 metric은 value, unit, windowMs, observedAt, source를 가진다.

플랫폼이 특정 지표를 안전하게 제공하지 못하면 **0을 만들지 않는다. value=null**로 반환한다.

서버 전체를 순회하거나 chunk를 새로 로드해서 지표를 계산하지 않는다.

## 5. get_online_players

입력:

~~~json
{
  "cursor": null,
  "limit": 50
}
~~~

제약:

- limit 1~100
- 반환 최대 100명
- UUID + 현재 이름만 반환
- IP, 접속 주소, 개인 채팅은 반환하지 않음
- 다음 페이지가 있으면 nextCursor 제공
- 전체 수를 정확히 모르면 추측하지 않음

현재 접속자 0명은 오류가 아니라 정상적인 빈 목록이다.

## 6. get_player

둘 중 하나만 사용한다.

~~~json
{"playerUuid": "uuid"}
~~~

또는:

~~~json
{"exactName": "Steve"}
~~~

v0.1은 prefix/fuzzy 검색을 하지 않는다. 단일 UUID로 확인할 수 없으면 AMBIGUOUS_TARGET, 존재하지 않으면 NOT_FOUND다.

반환:

- player.uuid
- player.name
- online=true

오프라인 profile 대량 검색은 초기 범위에 넣지 않는다.

## 7. get_player_location

입력:

~~~json
{"playerUuid": "uuid"}
~~~

제약:

- 대상은 현재 온라인이어야 한다.
- 현재 로드된 서버/월드 상태에서 읽는다.
- worldId, x/y/z, yaw/pitch와 observedAt를 반환한다.
- offline이면 NOT_FOUND.
- 좌표를 모델이 추정해서 보완하지 않는다.

## 8. get_nearby_players

입력:

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

제약:

- radius > 0, 최대 64 block
- limit 최대 100
- 같은 world만
- 거리 기준은 플랫폼 구현에서 일관되게 Euclidean distance
- 조회를 위해 chunk를 새로 load하지 않는다.
- 반환 배열에는 player와 distance를 포함한다.

## 9. get_world_info

입력:

~~~json
{"worldId": "world"}
~~~

반환 가능한 공통 정보:

- worldId
- dimensionKey
- playerCount
- difficulty
- timeOfDay

플랫폼에서 안전하게 제공하지 못하는 값은 null. world 자체를 찾지 못하면 NOT_FOUND다.

## 10. teleport_staff

입력:

~~~json
{"targetPlayerUuid": "uuid"}
~~~

top-level actionId 필수.

### 절대 규칙

- 이동 주체는 항상 requesterUuid다.
- arguments에 requester UUID를 받지 않는다.
- 대상 targetPlayerUuid는 실행 직전 online 여부와 위치를 다시 확인한다.
- requester도 실행 직전 online + OP를 다시 확인한다.
- arbitrary coordinate 이동은 지원하지 않는다.
- 다른 플레이어를 이동시키지 않는다.
- 단순 "Steve 어디 있어?" 질문에서는 호출하면 안 된다.
- 사용자가 실제로 자신을 대상에게 이동시켜 달라고 요청한 턴에서만 Brain이 제안한다.
- Jev 장애 fallback에서는 제공하지 않는다.

플랫폼 teleport API가 취소/실패하면 성공 응답을 만들지 않는다. 실제 완료를 확인한 뒤 completed=true를 반환한다.

timeout/ACK 손실로 결과가 불명확하면 OUTCOME_UNKNOWN이며 자동 재실행하지 않는다.

## 11. lookup_area_history — v0.1.1

Provider: CoreProtect.

입력:

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

제약:

- 기본 UX 범위: radius 10 / 최근 30분
- radius: 0~64
- lookbackSeconds: 1~86400
- limit: 1~100
- radius 0은 exact block 위치 조회 용도로 사용할 수 있음

Brain이 사용자의 "최근"을 해석할 때 기본값을 제안할 수 있지만, **실제 windowStart/windowEnd는 Adapter가 실행 시각을 기준으로 고정**한다.

반환:

- records 최대 100
- returnedCount
- totalCount: backend가 정확한 값을 제공하지 않으면 null
- nextCursor
- windowStart
- windowEnd

truncated=true는 결과가 없다는 뜻이 아니다.

CoreProtect 기록은 "이 actor 이름으로 이 변경이 기록됐다"는 사실만 제공한다. JARVIS가 의도/악의/그리핑 여부를 사실처럼 단정하지 않는다.

## 12. lookup_player_history — v0.1.1

입력:

~~~json
{
  "playerUuid": "uuid",
  "lookbackSeconds": 1800,
  "cursor": null,
  "limit": 100
}
~~~

시간/개수 상한은 area history와 동일하다.

Provider가 UUID 기반 조회를 직접 보장하지 못하고 이름 mapping에 의존해야 하면 그 한계를 source/error/운영 문서에 명시한다. 서로 다른 사용자를 같은 actor로 추정해서 합치지 않는다.

## 13. get_regions_at_location — v0.1.1

Provider: WorldGuard.

입력:

~~~json
{"location": {"worldId":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}}
~~~

반환 Region summary:

- id
- priority
- owners
- members

region 포함 관계와 priority는 WorldGuard API 결과를 따른다. JARVIS가 자체 region engine을 구현하지 않는다.

## 14. get_region_info — v0.1.1

입력:

~~~json
{
  "worldId": "world",
  "regionId": "spawn"
}
~~~

반환:

- id
- worldId
- priority
- parentId
- owners
- members
- serializable flags

flag 값은 모델에 넘기기 전에 단순 string/number/boolean/null DTO로 변환한다. Bukkit/WorldGuard object 자체를 protocol에 넣지 않는다.

## 15. check_build_permission — v0.1.1

입력:

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

반환 decision:

- ALLOW
- DENY
- UNDEFINED

추가 반환:

- bypass
- matchedRegions
- reason

보호 판정을 owner/priority/flag 조합으로 JARVIS가 재구현하지 않는다. WorldGuard RegionQuery를 사용하고 bypass는 별도 공식 API 판정을 반영한다.

## 16. Capability와 Tool 노출

Brain은 capabilities.tools에 들어온 Tool만 해당 server connection의 모델에 제공한다.

예:

- Fabric에 CoreProtect Provider 없음 -> history Tool 0개 노출
- Paper에 WorldGuard 없음 -> region Tool 0개 노출
- Provider version 불일치 -> plugin 파일이 있어도 Tool 0개 노출
- Provider가 runtime 장애 -> capability 제거 후 신규 요청에 미노출

Adapter는 Brain이 이미 Tool을 알고 있더라도 **실행 때 다시 capability를 검사한다.**

## 17. Argument 검증

모든 arguments는 strict schema다.

- unknown field 거부
- string -> number 자동 coercion 금지
- UUID/date-time format 검사
- 상한 초과를 clamp해서 몰래 실행하지 않고 INVALID_ARGUMENT
- 서버 object name을 입력값 그대로 command/SQL/code에 삽입하는 경로 없음

LLM이 생성한 값은 신뢰 입력이 아니다.

## 18. 데이터 최소화

Tool result에는 질문 해결에 필요한 데이터만 넣는다.

기본 제외:

- IP
- API key
- shared secret
- 전체 서버 로그
- private chat
- 인증 token
- filesystem path
- stack trace

Player name, UUID, 현재 위치, region/history 정보는 해당 OP 요청 해결에 필요한 범위에서만 외부 모델로 보낸다.

## 19. T03/T04/T05 인계

T03:

- schema의 Tool arguments/result DTO와 1:1 대응.
- Adapter-side allowlist, capability, thread bridge, result source/observedAt 구현.

T04:

- capability별 Tool registry 구성.
- request당 최대 Tool 8회.
- state-changing Tool은 fallback 경로에서 제외.
- requester/session/server binding 확인.

T05:

- Luna function schema는 이 catalog에서 생성.
- 모델이 schema 밖 인자를 만들면 재질문 또는 INVALID_ARGUMENT 처리.
- Jev category는 Tool 후보를 좁힐 뿐 실행 권한을 주지 않는다.
