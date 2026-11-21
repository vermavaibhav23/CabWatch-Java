// Bounded open-loop load. Reports missed generation slots instead of hiding generator overload.
// Only use against a deployment you own. Never turn results into an unmeasured capacity claim.
import { randomUUID } from "node:crypto";
import { writeFile } from "node:fs/promises";
const base = process.env.BASE_URL || "http://localhost:18081";
const rate = Number(process.env.RATE || 100),
  seconds = Number(process.env.SECONDS || 30),
  count = Number(process.env.VEHICLES || 200);
if (!process.env.OPERATOR_PASSWORD) throw Error("Set OPERATOR_PASSWORD");
let token,
  accepted = 0,
  failed = 0,
  missed = 0,
  inflight = 0;
const samples = [],
  tasks = new Set();
const batchSize = Number(process.env.BATCH_SIZE || 50);
async function api(path, body) {
  const response = await fetch(base + "/api/" + path, {
    method: body ? "POST" : "GET",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(20000),
  });
  if (!response.ok) throw Error(response.status);
  return response.json();
}
token = (await api("auth", { password: process.env.OPERATOR_PASSWORD })).token;
const vehicles = [];
for(let offset=0;offset<count;offset+=20){
 await Promise.all(Array.from({length:Math.min(20,count-offset)},async()=>{const id=randomUUID();await api('trips',{id,pickup:'A',dropoff:'Z'});vehicles.push({id,sequence:0,busy:false});}));
}
const start = performance.now();
let offered = 0,
  cursor = 0;
while (performance.now() - start < seconds * 1000) {
  const due = Math.floor(((performance.now() - start) * rate) / 1000);
  while (offered < due) {
    const selected = [],
      readings = [];
    while (offered < due && readings.length < batchSize) {
      offered++;
      let v;
      for (let j = 0; j < count; j++) {
        const candidate = vehicles[cursor++ % count];
        if (!candidate.busy) {
          v = candidate;
          break;
        }
      }
      if (!v) {
        missed++;
        continue;
      }
      v.busy = true;
      selected.push(v);
      readings.push({
        eventId: randomUUID(),
        tripId: v.id,
        sequence: ++v.sequence,
        capturedAt: Date.now(),
        x: 1400,
        y: 1400,
      });
    }
    if (!readings.length) continue;
    inflight++;
    const t = performance.now();
    const task = api("telemetry/batch", { readings })
      .then(() => {
        accepted += readings.length;
        samples.push(performance.now() - t);
      })
      .catch(() => (failed += readings.length))
      .finally(() => {
        selected.forEach((v) => (v.busy = false));
        inflight--;
        tasks.delete(task);
      });
    tasks.add(task);
  }
  await new Promise((r) => setTimeout(r, 10));
}
await Promise.allSettled([...tasks]);
const elapsed = (performance.now() - start) / 1000;
await new Promise((r) => setTimeout(r, 2000));
const metrics = await fetch(base + "/actuator/prometheus", {
  headers: { Authorization: "Bearer " + token },
}).then((r) => r.text());
samples.sort((a, b) => a - b);
const percentile = (p) =>
  samples[Math.min(samples.length - 1, Math.floor(samples.length * p))] ?? null;
const report = {
  at: new Date().toISOString(),
  targetReadingsPerSecond: rate,
  durationSeconds: seconds,
  vehicles: count,
  batchSize,
  offered,
  accepted,
  failed,
  generatorMissedSlots: missed,
  elapsedIncludingDrain: elapsed,
  acceptedPerSecond: accepted / elapsed,
  httpBatchAckMilliseconds: {
    p50: percentile(0.5),
    p95: percentile(0.95),
    p99: percentile(0.99),
  },
  note: "HTTP acknowledgment latency is not alert latency. See Prometheus detection histogram; it includes all runs since server startup. Test longer steady load, bursts, restarts and recovery before claiming capacity.",
};
await writeFile(
  process.env.REPORT || "load-report.json",
  JSON.stringify(report, null, 2),
);
await writeFile("load-metrics.prom", metrics);
console.log(JSON.stringify(report, null, 2));
