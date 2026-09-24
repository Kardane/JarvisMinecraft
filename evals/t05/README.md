# T05 model evaluation

## A09 Korean Jev routing set

`jev-korean-cases.jsonl` contains exactly 200 labeled Korean examples:

- 25 examples for each of the 8 routing labels
- 144 development examples
- 56 holdout examples
- honorific/informal language
- typos
- negation
- pronouns
- compound requests
- general conversation
- prompt-injection / hostile strings

The holdout split must not be used to select a confidence threshold.

After building Brain:

```bash
cd brain
npm run build
cd ..
TYPESAFE_API_KEY=... node evals/t05/run-jev-eval.mjs --output /tmp/jev-report.json
```

The evaluator chooses a threshold from the development split only, then reports:

- raw route accuracy
- per-class accuracy
- confusion matrix
- abstention rate
- coverage
- selective accuracy
- holdout 95% product target

A failing target is reported as a model gate failure; it must not be hidden by changing models.

## A10 model live smoke

After building Brain:

```bash
OPENAI_API_KEY=... TYPESAFE_API_KEY=... node evals/t05/live-models.mjs
```

The script records masked/non-secret evidence for:

- exact Jev model id
- Jev category/confidence/request ID
- exact Luna model id
- Luna response ID
- Luna function Tool call
- function result continuation
- final Luna response

The Tool result in this T05 smoke script is an explicit fixture. It is not Minecraft-server E2E evidence; that remains T10.
