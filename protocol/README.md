# Protocol compatibility assets

이 디렉터리는 Remote Brain 시절의 language-neutral wire contract를 보존한다.

- Schema: `schema/protocol.schema.json`
- JSON Schema draft: 2020-12
- Historical protocol version: `1.0`
- Valid/invalid fixture: `fixtures/`

E13/E14 이후 production runtime에는 Adapter ↔ Brain WebSocket 경계가 없다. 따라서 이 schema는 현재 JVM 내부 호출의 serialization contract가 아니라 다음 목적의 정적 자산이다.

- Embedded 전환 전후 정책/Tool shape 비교
- 과거 T10 evidence 해석
- 회귀 분석과 migration compatibility 참고
- contract constants의 역사적 source

권한, OP 재검사, active Tool, deadline, deduplication, `OUTCOME_UNKNOWN` 같은 안전 정책은 현재 Java `CommonRuntime`과 `EmbeddedBrain`에서 직접 집행한다.

새 production 기능을 추가할 때는 `ToolArgumentCodec`, `Protocol.ToolName`, Tool result model과 Embedded runtime 검증을 우선 기준으로 사용한다. 이 schema를 다시 network runtime으로 간주하지 않는다.
