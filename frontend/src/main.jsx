import React, { useState, useEffect, useRef } from "react";
import { createRoot } from "react-dom/client";
import {
  makeCab,
  advance,
  divert,
  rejoin,
  motionSnapshot,
} from "../motion.mjs";
import "./style.css";
function App() {
  const [password, setPassword] = useState(""),
    [email, setEmail] = useState(""),
    [name, setName] = useState(""),
    [loginMode, setLoginMode] = useState("login"),
    [auth, setAuth] = useState(""),
    [network, setNetwork] = useState(null),
    [rows, setRows] = useState([]),
    [error, setError] = useState(""),
    [running, setRunning] = useState(false),
    [pickup, setPickup] = useState("A"),
    [dropoff, setDropoff] = useState("Z");
  const [alerts, setAlerts] = useState([]),
    [history, setHistory] = useState(null);
  const cabs = useRef(new Map()),
    pending = useRef(new Map()),
    busy = useRef(false),
    latestRows = useRef([]);
  latestRows.current = rows;
  async function api(path, body) {
    const r = await fetch("/api/" + path, {
      method: body ? "POST" : "GET",
      headers: {
        Authorization: "Bearer " + auth,
        "Content-Type": "application/json",
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!r.ok)
      throw Error(
        "Request failed (" +
          r.status +
          "). Check credentials and service health.",
      );
    return r.json();
  }
  useEffect(() => {
    if (!auth) return;
    let active = true;
    api("network")
      .then((n) => active && setNetwork(n))
      .catch((e) => setError(e.message));
    return () => {
      active = false;
    };
  }, [auth]);
  useEffect(() => {
    if (!network || !auth) return;
    let alive = true;
    const refresh = async () => {
      try {
        const [data, events] = await Promise.all([api("state"), api("alerts")]);
        if (alive) {
          setRows(data);
          setAlerts(events);
        }
      } catch (e) {
        if (alive) setError(e.message);
      }
    };
    refresh();
    const t = setInterval(refresh, 1000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [network, auth]);
  useEffect(() => {
    if (!running || !network) return;
    const t = setInterval(async () => {
      if (busy.current) return;
      busy.current = true;
      try {
        const batch = [];
        const save = (id, c) =>
          localStorage.setItem(
            "cabwatch:" + id,
            JSON.stringify({
              point: c.point,
              sequence: c.sequence,
              motion: motionSnapshot(c),
              pending: pending.current.get(id),
            }),
          );
        for (const row of latestRows.current) {
          const trip = row.trip;
          if (trip.completed) continue;
          let c = cabs.current.get(trip.id);
          if (!c) {
            const saved = JSON.parse(
              localStorage.getItem("cabwatch:" + trip.id) || "null",
            );
            c = makeCab({ ...trip, motion: saved?.motion }, network);
            if (saved) {
              c.point = saved.point;
              c.sequence = saved.sequence;
              if (saved.pending) pending.current.set(trip.id, saved.pending);
            } else if (row.state.capturedAt) {
              c.point = [row.state.x, row.state.y];
              c.mode = "returning";
              c.recovery = [
                trip.route.reduce((best, n) =>
                  Math.hypot(
                    ...network.nodes[n].map((v, i) => v - c.point[i]),
                  ) <
                  Math.hypot(
                    ...network.nodes[best].map((v, i) => v - c.point[i]),
                  )
                    ? n
                    : best,
                ),
              ];
            }
            c.sequence = Math.max(c.sequence, row.state.sequence || 0);
            cabs.current.set(trip.id, c);
          }
          let reading = pending.current.get(trip.id);
          if (!reading) {
            if (!advance(c)) {
              await api("trips/" + trip.id + "/complete", {});
              continue;
            }
            reading = {
              eventId: crypto.randomUUID(),
              tripId: trip.id,
              sequence: c.sequence,
              capturedAt: Date.now(),
              x: c.point[0],
              y: c.point[1],
            };
            pending.current.set(trip.id, reading);
          }
          save(trip.id, c);
          batch.push(reading);
        }
        if (batch.length) {
          await api("telemetry/batch", { readings: batch });
          for (const reading of batch) {
            const c = cabs.current.get(reading.tripId);
            pending.current.delete(reading.tripId);
            save(reading.tripId, c);
            if (c.mode === "arrived")
              await api("trips/" + reading.tripId + "/complete", {});
          }
        }
      } catch (e) {
        setError(e.message);
      } finally {
        busy.current = false;
      }
    }, 1000);
    return () => clearInterval(t);
  }, [running, network, auth]);
  async function login(e) {
    e.preventDefault();
    try {
      const r = await fetch(
        loginMode === "operator" ? "/api/auth" : "/api/auth/" + loginMode,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ password, email, name }),
        },
      );
      if (!r.ok)
        throw Error(
          r.status === 409
            ? "This email is already registered. Sign in instead."
            : r.status === 429
              ? "Too many attempts. Please try again in a minute."
              : "Could not sign in or create an account. Check your details and password length (10 to 72 bytes).",
        );
      setAuth((await r.json()).token);
      setPassword("");
      setError("");
    } catch (e) {
      setError(e.message);
    }
  }
  async function dispatch() {
    try {
      await api("trips", { id: crypto.randomUUID(), pickup, dropoff });
    } catch (e) {
      setError(e.message);
    }
  }
  if (!auth)
    return (
      <main className="login">
        <h1>CabWatch</h1>
        <p>Java fleet telemetry lab</p>
        {error && <p role="alert">{error}</p>}
        <form onSubmit={login}>
          <label>
            Account access
            <select
              value={loginMode}
              onChange={(e) => setLoginMode(e.target.value)}
            >
              <option value="login">Sign in</option>
              <option value="register">Create account</option>
              <option value="operator">Operator access</option>
            </select>
          </label>
          {loginMode === "register" && (
            <label>
              Name
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                maxLength={80}
                autoComplete="name"
                required
              />
            </label>
          )}
          {loginMode !== "operator" && (
            <label>
              Email
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                maxLength={254}
                autoComplete="email"
                required
              />
            </label>
          )}
          <label>
            Password
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={loginMode === "operator" ? 16 : 10}
              maxLength={72}
              autoComplete={
                loginMode === "register" ? "new-password" : "current-password"
              }
              required
            />
          </label>
          <button>
            {loginMode === "register"
              ? "Create private fleet"
              : "Open dashboard"}
          </button>
        </form>
      </main>
    );
  const activeAlerts = rows.filter(
    (r) => !r.trip.completed && !r.delayed && r.state.offRoute,
  ).length;
  return (
    <main>
      <header>
        <div>
          <small>FLEET OPERATIONS / SIMULATED CITY</small>
          <h1>
            CabWatch <span>Java edition</span>
          </h1>
        </div>
        <div>
          <button onClick={() => setRunning(!running)}>
            {running ? "Pause simulator" : "Start simulator"}
          </button>
          <button
            onClick={() => {
              setRunning(false);
              setAuth("");
              setNetwork(null);
              setRows([]);
              setAlerts([]);
              setHistory(null);
              setError("");
              cabs.current.clear();
              pending.current.clear();
            }}
          >
            Sign out
          </button>
        </div>
      </header>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <section className="stats">
        <article>
          <b>{rows.length}</b> vehicles
        </article>
        <article>
          <b>{activeAlerts}</b> active deviations
        </article>
        <article>
          <b>{rows.filter((r) => r.delayed).length}</b> delayed / awaiting
          telemetry
        </article>
      </section>
      <div className="layout">
        <section className="map">
          <h2>Harbour City</h2>
          <svg viewBox="0 0 960 560" aria-label="Fleet map">
            {network?.roads.map((road) => {
              let a = network.nodes[road[0]],
                b = network.nodes[road[1]];
              return (
                <line
                  key={road}
                  x1={a[0]}
                  y1={a[1]}
                  x2={b[0]}
                  y2={b[1]}
                  stroke="#bccad8"
                  strokeWidth="3"
                />
              );
            })}
            {network &&
              rows.map((r) => (
                <polyline
                  key={r.trip.id}
                  points={r.trip.route
                    .map((n) => network.nodes[n].join(","))
                    .join(" ")}
                  fill="none"
                  stroke="#326ce5"
                  opacity=".3"
                  strokeWidth="5"
                />
              ))}
            {network &&
              Object.entries(network.nodes).map(([n, p]) => (
                <g key={n}>
                  <circle cx={p[0]} cy={p[1]} r="5" fill="#3b5265" />
                  <text x={p[0] + 8} y={p[1] - 8}>
                    {n}
                  </text>
                </g>
              ))}
            {rows
              .filter((r) => r.state.capturedAt)
              .map((r) => (
                <circle
                  key={r.trip.id}
                  cx={r.state.x}
                  cy={r.state.y}
                  r="10"
                  fill={
                    r.delayed
                      ? "#8793a0"
                      : r.state.offRoute
                        ? "#dc4242"
                        : "#16a085"
                  }
                >
                  <title>
                    {r.trip.id}: {r.delayed ? "DELAYED" : r.state.status}
                  </title>
                </circle>
              ))}
          </svg>
          <h3>Recent confirmed alerts</h3>
          {alerts.length === 0 ? (
            <p>No confirmed deviations yet.</p>
          ) : (
            <ul>
              {alerts.slice(0, 8).map((a) => (
                <li key={a.tripId + ":" + a.sequence}>
                  {a.tripId.slice(0, 8)}: {a.message}{" "}
                  <small>
                    {new Date(a.capturedAt).toLocaleTimeString()} | reading{" "}
                    {a.sequence}
                  </small>
                </li>
              ))}
            </ul>
          )}
          <p>
            Grey vehicles have outdated locations. Map positions reflect
            processed readings, not optimistic simulator positions.
          </p>
        </section>
        <aside>
          <h2>Dispatch a trip</h2>
          <label>
            Pickup
            <select value={pickup} onChange={(e) => setPickup(e.target.value)}>
              {network &&
                Object.keys(network.nodes).map((n) => (
                  <option key={n}>{n}</option>
                ))}
            </select>
          </label>
          <label>
            Drop-off
            <select
              value={dropoff}
              onChange={(e) => setDropoff(e.target.value)}
            >
              {network &&
                Object.keys(network.nodes).map((n) => (
                  <option key={n}>{n}</option>
                ))}
            </select>
          </label>
          <button onClick={dispatch}>Add trip</button>
          <h2>Vehicle status</h2>
          {rows.map((r) => (
            <article className="vehicle" key={r.trip.id}>
              <strong>
                {r.trip.pickup} to {r.trip.dropoff}
              </strong>
              <small>{r.trip.id.slice(0, 8)}</small>
              <p>
                {r.trip.completed
                  ? "COMPLETED"
                  : r.delayed
                    ? "DELAYED"
                    : r.state.offRoute
                      ? "OFF ROUTE"
                      : r.state.status}{" "}
                |{" "}
                {r.state.capturedAt
                  ? Math.max(
                      0,
                      (r.serverTime - r.state.capturedAt) / 1000,
                    ).toFixed(1) + "s old"
                  : "Awaiting data"}
              </p>
              <button
                onClick={async () => {
                  try {
                    setHistory({
                      id: r.trip.id,
                      readings: await api("trips/" + r.trip.id + "/history"),
                    });
                  } catch (e) {
                    setError(e.message);
                  }
                }}
              >
                History
              </button>
              <button
                disabled={r.trip.completed}
                onClick={() => {
                  const c = cabs.current.get(r.trip.id);
                  if (c) divert(c);
                }}
              >
                Detour
              </button>
              <button
                onClick={() => {
                  const c = cabs.current.get(r.trip.id);
                  if (c) rejoin(c);
                }}
              >
                Return
              </button>
            </article>
          ))}
        </aside>
      </div>
      {history && (
        <section className="history">
          <h2>Stored readings: {history.id.slice(0, 8)}</h2>
          <button onClick={() => setHistory(null)}>Close history</button>
          <p>
            First 100 of {history.readings.length} returned records. The history
            API supports sequence-based pagination.
          </p>
          <table>
            <thead>
              <tr>
                <th>Sequence</th>
                <th>Captured</th>
                <th>X</th>
                <th>Y</th>
              </tr>
            </thead>
            <tbody>
              {history.readings.slice(0, 100).map((r) => (
                <tr key={r.event_id}>
                  <td>{r.sequence}</td>
                  <td>{new Date(r.captured_at).toLocaleTimeString()}</td>
                  <td>{r.x.toFixed(1)}</td>
                  <td>{r.y.toFixed(1)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
      <footer>
        Historical telemetry is processed independently. Freshness target: 2s;
        warning: 5s; stale: 10s. These are test targets, not guaranteed service
        levels.
      </footer>
    </main>
  );
}
createRoot(document.getElementById("root")).render(<App />);
