# Later Work

This file lists only items that remain after the current Phase 1–8 and Operational Logging L1–L6 implementation.

## Admin Commands

- `/jm test`
  - provide a diagnostic command for safely checking Provider/Jev/Luna/Tool boundaries
  - never print secrets or raw prompts

## Verification Improvements

- formalize Java verification for protocol fixtures as a Gradle task
- expand deterministic coverage for scheduling/ACTIVE-mode races
- add regression coverage for operational logging events and secret masking
- add Audit health degraded/recovered fault-injection tests

## Operability

- consider a metrics exporter if needed
  - request latency/count
  - Jev/Luna latency
  - Tool latency/error count
  - AI queue depth
  - Audit queue depth
  - proactive candidate/accept count
- evaluate rate limiting for repeated warnings
