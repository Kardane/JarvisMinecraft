# Protocol Compatibility Assets

This directory preserves the language-neutral wire contract from the Remote Brain era.

- Schema: `schema/protocol.schema.json`
- JSON Schema draft: 2020-12
- Historical protocol version: `1.0`
- Valid/invalid fixtures: `fixtures/`

After E13/E14, the production runtime no longer has an Adapter ↔ Brain WebSocket boundary. This schema is therefore a static compatibility asset rather than the serialization contract for current in-JVM calls. It is retained for:

- comparing policy and Tool shapes before and after the Embedded migration
- interpreting historical T10 evidence
- regression analysis and migration-compatibility reference
- historical provenance of contract constants

Safety policies such as authority checks, OP re-validation, active Tool checks, deadlines, deduplication, and `OUTCOME_UNKNOWN` are now enforced directly by Java `CommonRuntime` and `EmbeddedBrain`.

For new production features, treat `ToolArgumentCodec`, `Protocol.ToolName`, Tool result models, and Embedded runtime validation as the primary contract. Do not treat this schema as a network runtime again.
