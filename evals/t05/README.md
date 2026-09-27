# T05 Model Evaluation Assets

When the TypeScript/Node production Brain was removed in E14, the Node-based T05 runner was removed as well. The evaluation datasets and existing evidence are retained.

## Jev Korean Routing Set

`jev-korean-cases.jsonl` retains the existing 200-case Korean Jev routing dataset. It includes:

- 8 routing labels
- development / holdout split
- honorific / casual speech
- typos
- negation
- pronouns
- compound requests
- general conversation
- hostile / prompt-injection strings

Reuse this dataset unchanged if a Java Jev evaluation runner is added in the future.

## Current Live-Model Verification

The Embedded Java path uses this Gradle task for live Provider smoke testing:

```bash
OPENAI_API_KEY=... TYPESAFE_API_KEY=... \
  ./gradlew :minecraft:common:embeddedBrainLiveVerification
```

E11 verification exercises real Jev and Luna Provider calls. Minecraft platform E2E and live-model smoke tests are separate evidence layers.

## E12 Parity

`evals/embedded-policy-parity.json` is the shared policy fixture finalized in E12. It remains the Java parity-regression input after removal of the Node reference implementation.
