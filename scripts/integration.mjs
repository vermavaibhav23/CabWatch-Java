import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";

const base = process.env.BASE_URL || "http://localhost:18081";
if (!/^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(base))
  throw Error("Run destructive outage checks only on localhost");
const password = process.env.OPERATOR_PASSWORD;
if (!password) throw Error("Set OPERATOR_PASSWORD");
let token;
async function request(path, body, expected = 200) {
  const r = await fetch(base + "/api/" + path, {
    method: body ? "POST" : "GET",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(20000),
  });
  assert.equal(r.status, expected, await r.clone().text());
  return r.json();
}
async function eventually(check, description, timeout = 30000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    try {
      if (await check()) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 150));
  }
  throw Error("Timed out: " + description);
}
const docker = (...args) =>
  execFileSync("docker", args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  }).trim();
token = (await request("auth", { password })).token;
assert.equal((await fetch(base + "/api/state")).status, 401);
assert.equal((await fetch(base + "/actuator/prometheus")).status, 401);
const id = randomUUID();
await request("trips", { id, pickup: "A", dropoff: "Z" });
const reading = (sequence, time = Date.now(), x = 1400, y = 1400) => ({
  eventId: randomUUID(),
  tripId: id,
  sequence,
  capturedAt: time,
  x,
  y,
});
const state = async () =>
  (await request("state")).find((r) => r.trip.id === id);
const send = async (r) => request("telemetry", r, 202);
const waitSequence = (n) =>
  eventually(
    async () => (await state()).state.sequence === n,
    "live sequence " + n,
  );

// Two bad points do not alert. The third confirms the deviation.
for (let i = 1; i <= 3; i++) {
  await send(reading(i));
  await waitSequence(i);
  assert.equal((await state()).state.offRoute, i === 3);
}
const duplicate = reading(4, Date.now() - 300000);
await send(duplicate);
await send(duplicate);
await waitSequence(4);
let s = await state();
assert.equal(s.delayed, true);
assert.equal(s.state.consecutive, 0);
assert.equal(s.state.offRoute, false);
for (let i = 5; i <= 7; i++) {
  await send(reading(i));
  await waitSequence(i);
  assert.equal((await state()).state.offRoute, i === 7);
}
await send(reading(9));
await waitSequence(9);
assert.equal((await state()).state.consecutive, 1);
await send(reading(8));
await new Promise((r) => setTimeout(r, 300));
assert.equal((await state()).state.sequence, 9);
await eventually(
  async () => (await request("trips/" + id + "/history")).length === 9,
  "history includes stale/out-of-order samples exactly once",
);
assert.equal((await request("trips/" + id + "/history?after=5")).length, 4);
await request("telemetry", reading(10, Date.now() + 60000), 400);
console.log(
  "PASS: authentication, three readings, stale recovery, reset, duplicates, ordering and complete history",
);

if (process.env.OUTAGE_TEST === "1") {
  const container = "cabwatch-java-test-postgres-1";
  docker("stop", "--time", "2", container);
  try {
    await send(reading(10));
    await eventually(() => {
      const raw = docker(
        "exec",
        "cabwatch-java-test-redis-1",
        "redis-cli",
        "GET",
        "live:" + id,
      );
      return raw && JSON.parse(raw).sequence === 10;
    }, "live detection continues with history database stopped");
    console.log("PASS: live detection continues during PostgreSQL outage");
  } finally {
    docker("start", container);
  }
  await eventually(
    async () => (await request("trips/" + id + "/history")).length === 10,
    "history catches up after database restart",
    60000,
  );
  console.log("PASS: history recovers every sample after database restart");
}
// Several transitions in one Kafka poll must retain a brief confirmed alert.
const now=Date.now();
const network=await request('network');
const [x,y]=network.nodes.A;
await request('telemetry/batch',{readings:[reading(11,now-300000),reading(12,now),reading(13,now+1),reading(14,now+2),reading(15,now+3,x,y),reading(16,now+4)]},202);
await waitSequence(16);
assert.equal((await state()).state.offRoute,false);
await eventually(async()=> (await request('alerts')).some(a=>a.tripId===id && a.sequence===14),'brief batch alert retained');
await eventually(async()=> (await request('trips/'+id+'/history')).length===(process.env.OUTAGE_TEST==='1'?16:15),'batch history complete');
console.log('PASS: batched ingestion and transient alert retention');
await request("trips/" + id + "/complete", {});
assert.equal((await state()).trip.completed, true);
console.log("PASS: trip completion");
