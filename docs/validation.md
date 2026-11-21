# Validation record

These measurements describe the local Windows/Docker test environment, not Render.

## Passed checks

- Java unit tests: 7, including geometry and the live detection state machine.
- JavaScript movement tests: 6, including detours, return paths and checkpoint recovery.
- Vite production build and npm audit (zero reported vulnerabilities at verification).
- Real Kafka, PostgreSQL and Redis integration: authenticated ingestion, third-reading
  confirmation, stale recovery, reset after missing sequences, duplicate/out-of-order
  handling, history completeness and trip completion.
- Stopping the isolated PostgreSQL container did not stop live processing of an
  already cached trip. History caught up after the database restarted.
- Batched transitions retain a brief confirmed alert even when later samples in the
  same batch return to the planned route.
- Account regression checks cover signup, login, duplicate emails, incorrect
  passwords, private fleet lists/history/alerts, cross-account writes, atomic
  rejection of a mixed-owner batch, and operator-only access to metrics.
- Render Blueprint validated against Render's published JSON schema.

## Capacity result

A bounded 15-second run targeted 3,000 readings/second across 1,500 simulated vehicles.
This benchmark was captured at commit `ab9ae5d`, before the subsequent account-isolation change.
It accepted 19,026 readings with zero HTTP failures, about 1,261/second including
acknowledgment drain. The generator missed 24,928 scheduled slots. This **does not
establish** the requested 2,000/second capacity or spare headroom. HTTP acknowledgment
p95 was about 1.77 seconds; that is not alert latency. The attached Prometheus dump
also includes functional tests performed since server startup and must not be
presented as a clean, isolated latency percentile for this run.

The local machine was running the application, Kafka, Redis, PostgreSQL and the load
generator together. Repeat longer steady-state, burst and restart benchmarks on the
actual deployment, inspect end-to-end age and both consumer groups, and provision
capacity based on those results. No guarantee that backlog can never occur is made.

Raw results: [JSON](benchmarks/local-3000-target.json) and
[filtered Prometheus snapshot](benchmarks/local-3000-target.prom).

## Remaining deployment checks

- Render resources have not yet been provisioned or billed.
- Render's account-specific estimate and hosting authorization are pending.
- Browser visual verification was blocked by an in-app browser connection failure;
  the frontend was compiled and its underlying movement logic was tested.
- The single-broker deployment is a demonstration, not a highly available fleet system.
