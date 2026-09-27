# Historical T10 Acceptance Evidence

After E14, this directory is no longer an executable Node acceptance harness. It serves as a **historical T10 evidence archive**.

Retained artifacts:

- `out/paper.json`
- `out/fabric.json`
- `out/neoforge.json`
- `out/a09-jev-report.json`
- `out/a10-live-models-report.json`

The former core/live Node runners and WebSocket acceptance gateway were removed with the production Remote Brain. Do not reinterpret these result files as evidence that the current Embedded runtime has passed a new E2E run.

Current deterministic verification:

```bash
./gradlew build
```

Current live Jev/Luna smoke test:

```bash
OPENAI_API_KEY=... TYPESAFE_API_KEY=... \
  ./gradlew :minecraft:common:embeddedBrainLiveVerification
```

If a new platform live-acceptance harness is needed, add one that boots the Embedded Brain directly.
