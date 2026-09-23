# Protocol fixtures

This directory is the language-neutral contract shared by the Java Minecraft adapters and the TypeScript Brain.

- Schema: `schema/protocol.schema.json`
- JSON Schema draft: 2020-12
- Current protocol: `1.0`
- Validators MUST enable UUID/date-time format checks and MUST NOT coerce input values.
- Every file in `fixtures/valid/` must validate.
- Every file in `fixtures/invalid/` must fail validation.
- `fixtures/manifest.json` is the common test manifest consumed by both runtimes once T02 creates build/test entry points.

Schema validation is only the first gate. Runtime semantic checks defined in `docs/protocol.md` still apply, including the 64 KiB UTF-8 message limit, deadline ordering, authenticated server/session binding, active capability checks, OP revalidation, and action deduplication.
