# Operations and capacity

## Deployment boundary

`render.yaml` describes four **new** Singapore resources. It must never be pointed
at the original CabWatch database or service. Render resources with matching names
may be updated, so check that these names are unused before creating the Blueprint.
Review the current price estimate before provisioning. No paid service has been
authorized merely by committing this file.

The web service is HTTPS. Kafka is a private service, and PostgreSQL/Redis reject
public network connections. The Kafka starter is a **single broker** with a disk,
not a highly available cluster. It can recover retained history after a restart,
but it cannot survive loss of its only disk. For real fleets, use three brokers,
replication factor 3, minimum in-sync replicas 2, authenticated TLS and tested backups.

Set a random OPERATOR_PASSWORD of at least 16 characters. Never commit it. The
login issues an eight-hour signed bearer token kept only in browser memory. Changing
the password invalidates all existing tokens. The dashboard is a single-operator
demo; separate customer accounts and tenant authorization are not implemented.

## Freshness behavior

- Normal target: creation to published detection state below 2 seconds.
- Warning: above 5 seconds. Dashboard polling adds up to about 1 second when healthy.
- Stale: above 10 seconds; skip detection, clear the consecutive counter, retain
  the last known fresh position and display DELAYED.
- A missing sequence, reversed timestamp, or long capture-time gap resets the check.
- Recovery starts from the first new reading and needs three new consecutive
  off-route samples before producing an alert. Historical samples never prove
  the vehicle's current location.
- Consumer batches preserve per-key order and update Redis once per vehicle.
  They still read Kafka sequentially; this policy does not promise bounded recovery
  if producers permanently outrun consumers.
- No second polling path bypasses Kafka for live locations. The browser polls
  processed state, not raw vehicle positions.

Recent alert transitions and their matching state are written atomically to Redis.
The last 200 alert events are retained for the UI; these are not an indefinite
audit log. PostgreSQL is the full telemetry record, including stale/out-of-order
samples. Retries of an event or trip/sequence do not create duplicate history rows.
Exactly-once delivery is not claimed. Kafka offset commits happen after successful
state/history writes; replay and idempotency handle interrupted commits.

## Capacity tests

Run `node scripts/load.mjs` with OPERATOR_PASSWORD, RATE, SECONDS, VEHICLES and
BATCH_SIZE in the environment. Example: RATE=3000, SECONDS=120, VEHICLES=3000,
BATCH_SIZE=50. The generator reports missed slots and failed requests rather than
claiming it delivered the requested rate. Run only against infrastructure you own.
Do not run high load on a constrained laptop and then extrapolate a production SLA.

Check both consumer groups, not merely HTTP acknowledgment throughput. Increase
live consumer concurrency up to the partition count; more consumers than partitions
are idle. Measure bursts, broker restarts, live worker restarts, Redis outages and
history database outages. Keep sustained measured capacity above expected peak
load. Partition keys are trip IDs, with one simulated vehicle per trip. Multiple
producer tabs controlling the same trip are not supported.

Metrics at authenticated `/actuator/prometheus` include:

- `cabwatch_live_detection_latency_seconds`: creation to published state, including Redis.
- `cabwatch_alert_latency_seconds`: creation to a published confirmed alert event.
- `cabwatch_reading_age_seconds`: capture-to-processing age, including skipped samples.
- `cabwatch_live_stale_skipped_total` and `cabwatch_live_latency_warnings_total`.
- Kafka client metrics including per-partition consumer lag.

Keep device clocks synchronized. The browser simulator uses local capture time.
Future timestamps more than two seconds ahead are rejected at ingestion.

## Outage regression

`scripts/integration.mjs` exercises authenticated ingestion, retries, ordering,
three-point confirmation, stale recovery and history completeness. Setting
OUTAGE_TEST=1 stops/restarts **only** `cabwatch-java-test-postgres-1`. It refuses
non-localhost targets. Cached active trips continue live detection during that
database outage; creating new trips and uncached route lookup still require PostgreSQL.

History persistence is independently committed in its own consumer group. The two
groups currently share one JVM, so CPU, heap exhaustion and process failure are
shared failure modes. Use LIVE_ENABLED/HISTORY_ENABLED to split workers into separate
deployments when stronger resource isolation is required. Malformed Kafka records
block their partition with retries; only the validated ingestion service should
be allowed to produce. Investigate and repair poison records instead of silently
discarding potentially required history.

Simulator movement/outbox checkpoints live in this browser's local storage. They
contain only fictional city positions, no credentials. If no checkpoint exists,
the simulator resumes from the last processed position and returns to the nearest
planned route intersection. Completed trips stop generating readings.
