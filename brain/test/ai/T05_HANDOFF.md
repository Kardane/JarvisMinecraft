# T05 Handoff — Luna / Jev integration

Date: 2026-09-24

## Baseline

- Base branch: codex/t04-brain-core
- T05 implements T04 ModelPort.
- Owned implementation: brain/src/ai/**
- Owned tests: brain/test/ai/**
- Owned evaluation assets: evals/**

## Pinned providers

- OpenAI SDK: 7.22.0
- Luna model: gpt-6-luna
- Responses API
- reasoning effort: medium
- OpenAI SDK retries: disabled

- TypeSafe SDK: 0.6.0
- Jev model: jev-1.13.0
- Jev deadline: 3000 ms
- TypeSafe SDK retries: disabled

No automatic provider/model substitution exists.

## Routing policy

Jev emits one of:

- SERVER_QUERY
- PLAYER_QUERY
- WORLD_QUERY
- HISTORY_QUERY
- REGION_QUERY
- ACTION_REQUEST
- GENERAL
- UNCERTAIN

Jev output only narrows Tool candidates; it never grants permission.

Fallback cases:
- Jev error / 429 / timeout
- Jev UNCERTAIN
- confidence below a threshold that has been selected on the development set

Fallback exposes read-only Tools only. It never exposes teleport_staff.

There is intentionally no default numeric low-confidence threshold before A09 live calibration. The threshold is a policy input; the evaluator selects it on the dev split and never tunes on holdout.

## Luna Tool loop

OpenAiLunaPort uses the official Responses API and manual conversation state.

- complete response.output items are normalized with toResponseInputItems
- function call IDs are preserved
- Adapter Tool results return as function_call_output
- Tool output is JSON encoded
- store=false
- exact active Tool schemas only
- strict=true for every function
- arguments are locally revalidated after model output

The core get_player Tool is represented to Luna as two strict functions:
- get_player_by_uuid
- get_player_by_name

Both translate back to core Tool get_player.

## A09 assets

evals/t05/jev-korean-cases.jsonl:
- 200 cases
- 25/class
- dev 144
- holdout 56
- required Korean style/adversarial tags

evals/t05/run-jev-eval.mjs:
- calls the real pinned Jev model
- records request IDs
- selects confidence threshold on dev only
- reports per-class accuracy, confusion matrix, abstention, coverage and selective accuracy
- reports the holdout 95% route-accuracy product target

A09 live accuracy is not claimed until the script is run with TYPESAFE_API_KEY.

## A10 smoke asset

evals/t05/live-models.mjs validates:
- real Jev model ID/category/confidence/request ID
- real gpt-6-luna function Tool call
- function_call_output continuation
- final Luna response
- no key output

The Tool result in T05 live smoke is explicitly simulated. Actual Minecraft Tool E2E remains T10.

## Remaining gates

- T05 unit tests must pass in CI.
- A09 live run requires TypeSafe API credentials and incurs external usage.
- A10 live run requires both provider credentials and incurs external usage.
- This task does not claim actual Paper/Fabric/NeoForge server E2E.
