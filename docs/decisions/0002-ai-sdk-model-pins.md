# ADR-0002: AI SDK and Model Version Pins

- Status: Accepted
- Date: 2026-09-24
- Related work: T00

## Context

JARVIS uses OpenAI GPT-6 Luna for generation/Tool orchestration and TypeSafe AI Jev for request classification. Using aliases or version ranges could let model/SDK updates silently change evaluation results and Tool behavior.

## Decision

Pin the initial T02/T05 baseline as follows:

- Node.js: 24 LTS
- OpenAI JS SDK: 7.22.0
- OpenAI Java SDK: 4.69.2 (Embedded Brain JVM bridge)
- OpenAI model: `gpt-6-luna`
- API: Responses API
- reasoning effort: `medium`
- TypeSafe SDK: `@typesafe-ai/sdk` 0.6.0
- TypeSafe model: `jev-1.13.0`

Do not use movable aliases such as `jev-latest` as the product evaluation baseline. Do not automatically substitute another model if either provider fails.

Jev is responsible only for classification:

- SERVER_QUERY
- PLAYER_QUERY
- WORLD_QUERY
- HISTORY_QUERY
- REGION_QUERY
- ACTION_REQUEST
- GENERAL
- UNCERTAIN

Authority, approval, time calculations, and whether a server mutation occurs are determined by deterministic code rather than model output.

## Failure Policy

- Jev timeout/error/low confidence: use a read-only Luna path or ask a clarifying question. Do not expose mutation Tools.
- Luna failure: use the fixed failure response. Do not invent numeric values or successful outcomes.
- Do not stack SDK retries on top of application retries.

The rationale for using Jev and Luna together, the routing/authority split, and the distinction between Brain request binding and Minecraft OP authority are fixed separately in [ADR-0005](0005-jev-luna-routing-authority-boundary.md).

## Consequences

Benefits:

- Evaluation results in A09/A10 can be attributed to a specific model/SDK combination.
- Reduces the risk that a moving alias invalidates classification thresholds.
- Prevents provider failures from expanding authority or triggering automatic mutations.

Costs:

- SDK security/compatibility patches require an explicit version change and regression verification.

## Verification Status

T00 verified documentation and public package/releases. No paid live call using real API keys was made at that stage. Evidence for `gpt-6-luna` Tool call/result behavior and `jev-1.13.0` request IDs is captured separately in T05/T10.

## Official References

- GPT-6 Luna: https://developers.openai.com/api/docs/models/gpt-6-luna
- OpenAI function calling: https://developers.openai.com/api/docs/guides/function-calling
- OpenAI Node SDK: https://github.com/openai/openai-node
- OpenAI Java SDK: https://github.com/openai/openai-java
- TypeSafe models: https://docs.typesafe.ai/models
- TypeSafe JavaScript SDK: https://docs.typesafe.ai/sdk/javascript
