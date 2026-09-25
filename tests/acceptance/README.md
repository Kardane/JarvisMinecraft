# T10 acceptance harness

This directory owns v0.1 acceptance evidence. It deliberately separates deterministic policy tests from live Minecraft/model evidence.

## Deterministic acceptance

Prerequisite:

```bash
cd brain
npm ci
npm run build
cd ../tests/acceptance
npm install --ignore-scripts --no-audit --no-fund
npm run test:core
```

The core suite maps tests to A01-A08 and A11-A13. A14 is v0.2-only.

## Live platform harness

The live harness uses:

- the packaged JARVIS Adapter JAR;
- a real Minecraft 1.21.8 dedicated server;
- the production `BrainWebSocketServer` transport on loopback;
- real offline-mode Minecraft protocol clients through Mineflayer;
- deterministic BrainCore ModelPort logic so Minecraft/platform behavior can be tested without provider cost.

Run one platform at a time:

```bash
npm run live:paper
npm run live:fabric
npm run live:neoforge
```

The harness creates isolated directories under `.t10/`, uses ports 25565 and 8181, and stops the server after the scenario.

The acceptance gateway is now only a thin test harness around the production `BrainWebSocketServer`. It supplies a deterministic ModelPort and in-memory audit observer, but does not duplicate WebSocket authentication, handshake, binding, reconnect, Tool correlation, or cancellation logic.

Therefore live platform tests exercise the same production transport implementation used by `npm start`. External A09/A10 still require the real Jev and GPT-6 Luna providers and are recorded separately.

## External model evidence

A09/A10 use the existing T05 scripts and require real credentials:

```bash
cd brain && npm ci && npm run build && cd ..
TYPESAFE_API_KEY=... node evals/t05/run-jev-eval.mjs --output /tmp/jev-report.json
OPENAI_API_KEY=... TYPESAFE_API_KEY=... node evals/t05/live-models.mjs
```

Never commit keys or generated credential-bearing environment files.
