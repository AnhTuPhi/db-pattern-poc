# 15 - DB Connection Pool (HikariCP knobs that decide whether you survive load)

> Pool sizing is math, not vibes. Every knob has a production failure
> story behind it. This module exposes the live `HikariPoolMXBean`,
> stress-tests the pool from inside the JVM, and ships the Wooldridge
> sizing formula as an endpoint so the math is on the table next to the
> demo.

## Thesis

The default HikariCP behaviour is *almost* right for a default web service.
Production outages happen at the edges:

- **`maximum-pool-size` too high** → Postgres backends overload, p99 climbs.
- **`maximum-pool-size` too low** → request threads queue, `connection-timeout`
  fires, the service brownouts.
- **`max-lifetime` left at default** → a firewall or PG-side idle killer
  closes connections under you and Hikari can't tell why.
- **`leak-detection-threshold` off** → a missing `close()` in a single
  code path silently empties the pool, the next request waits forever.

This module reproduces those failure shapes from a single Spring Boot app
so you can play with the knobs in `application.yml` and watch the
behaviour change in `/pool/inspect`.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 15-db-connection-pool -am spring-boot:run
```

App boots on **:8215**.

## Run order

```bash
# 1. Inspect the live pool — the configured knobs are here too.
curl 'localhost:8215/pool/inspect' | jq

# 2. Sizing math — the Wooldridge formula + an opinion on the answer.
curl 'localhost:8215/pool/sizing-math?cores=8&spindles=2&workloadFactor=2.0' | jq

# 3. Stress — fewer borrowers than the pool. Should drain cleanly.
curl 'localhost:8215/pool/stress?threads=5&holdMs=200' | jq

# 4. Stress — at the cap. Every connection in flight, no timeouts.
curl 'localhost:8215/pool/stress?threads=10&holdMs=500' | jq

# 5. Stress — over the cap AND over connection-timeout. THIS is the
#    failure mode — half the borrows time out at 55P03.
curl 'localhost:8215/pool/stress?threads=20&holdMs=2000' | jq

# 6. Lifetime rotation — 12s sample window, watch idle/active drift.
curl 'localhost:8215/pool/lifetime-rotation' | jq
```

## The knobs that matter

```yaml
spring.datasource.hikari:
  maximum-pool-size:         10        # the headline knob
  minimum-idle:              2         # keep N warm; default = maximum-pool-size
  connection-timeout:        2000      # ms; throws SQLTransientConnectionException
  idle-timeout:              60000     # retire idle connections above minimum-idle
  max-lifetime:              300000    # retire ANY connection past this age
  validation-timeout:        1000      # "is this conn alive?" check timeout
  leak-detection-threshold:  0         # ms; 0 = OFF. Module 16 turns this ON.
```

### `maximum-pool-size` — the headline knob

The Brett Wooldridge sizing formula:

```
connections = ((core_count * 2) + effective_spindle_count)
```

On a 4-core dev box with SSD-only storage (spindles ≈ 1) that's `(4*2)+1 = 9`.
On an 8-core production box with attached storage (spindles ≈ 2) that's `(8*2)+2 = 18`.

**It is a starting point, not the answer.** The real answer is to measure
the p95/p99 latency curve as you increase the pool, and pick the knee.
Going above the knee:
- More borrowers per Postgres backend → context-switch storm.
- Per-backend memory adds up — `max_connections` is a budget, shared with
  every replica of your app.
- Hikari's queue gets shorter but Postgres' query latency gets longer.

`/pool/sizing-math` prints the formula's result + an opinion banded by
size.

### `connection-timeout` — the cliff edge

When the pool is at `maximum-pool-size` and all connections are in use, a
new borrower waits in Hikari's queue. After `connection-timeout`, it
throws `SQLTransientConnectionException` (SQLState `08001`).

In production this is the **canary signal**: when you start seeing this
exception in logs, the pool is too small for the offered load. The
cascading failure mode is request threads piling up, GC pressure, then
service health checks failing.

`/pool/stress?threads=20&holdMs=2000` reproduces this. With `maximum-pool-size=10`
and `connection-timeout=2000`, half the 20 borrowers fail at `08001`
within 2 seconds.

### `max-lifetime` — the firewall guard

Many networks have a stateful firewall or load balancer that silently
drops TCP connections idle for >5 minutes. Many Postgres clusters have an
`idle_in_transaction_session_timeout` for the same reason. If your
connection is older than that threshold, the next query gets:

```
SocketException: Connection reset
```

…with no useful context. Setting `max-lifetime` below the upstream
threshold means Hikari retires the connection on return BEFORE the
upstream kills it. Standard production value: **300000 ms (5 minutes)**
for environments where the upstream kills at 10–15.

### `idle-timeout` — pool shrinkage

The pool stays at `maximum-pool-size` if traffic warrants it. After
`idle-timeout` of no demand, Hikari retires idle connections down to
`minimum-idle`. The trade-off:

- Short idle-timeout → pool reacts fast to load drops, less wasted resource.
- Long idle-timeout → fewer pool fills, fewer cold-start spikes.

For a service that sees diurnal load, leave the default 60s. For a
batch service that sees burst-then-quiet patterns, raise this so the pool
doesn't churn between bursts.

### `leak-detection-threshold` — turn this ON in prod

Off by default. When set to ms, Hikari logs a warning + stacktrace if a
borrowed connection isn't returned within the threshold. Tuning:

- Below your p99 query latency → false positives.
- Above your p99 → real leaks (forgot `close()`, broken `try-with-resources`).

**Standard production value: 10000 ms.** Module 16 demonstrates what
happens when this is off and a code path leaks one connection per call.

## What `/pool/stress` outputs

```json
{
  "threadsRequested": 20,
  "holdMs": 2000,
  "acquiredAndCompleted": 10,
  "timedOutAtBorrow": 10,
  "otherErrors": 0,
  "elapsedMs": 4023,
  "postRunPoolState": {
    "activeConnections": 0,
    "idleConnections": 2,
    "totalConnections": 2,
    "threadsAwaitingConnection": 0
  },
  "interpretation": "10 of 20 borrows timed out at connection-timeout..."
}
```

- **`acquiredAndCompleted`**: borrowed, ran the `pg_sleep`, returned cleanly.
- **`timedOutAtBorrow`**: `SQLTransientConnectionException`. The borrower
  never got a connection.
- **`postRunPoolState`**: live counters from the MXBean *after* the run.
  `idleConnections` drops to `minimum-idle` after the burst finishes.

## Production checklist

| Symptom                                                | Likely cause                            | Fix                                                |
|--------------------------------------------------------|-----------------------------------------|----------------------------------------------------|
| `SQLTransientConnectionException` spike under load     | `maximum-pool-size` too small           | Raise pool, OR shorten the held work, OR bulkhead (see 17). |
| `Connection reset` errors at off-peak                  | Firewall killed an idle connection      | Lower `max-lifetime` below the upstream threshold. |
| Pool drifts to 0 idle, never returns                   | Connection leak                         | Turn on `leak-detection-threshold` (see 16).      |
| p99 climbs as pool grows                               | Pool > Wooldridge knee                  | Shrink pool to the knee. Less is more.            |
| Health-check sees `validation-timeout`                 | Network or Postgres slowness            | Investigate before raising the knob.              |

## Files

```
src/main/java/com/claude/dbpoc/m15/
├── Application.java
├── service/
│   └── PoolStressService.java     # MXBean reads, stress, sizing math
└── web/
    └── PoolController.java        # /pool/{inspect,stress,sizing-math,lifetime-rotation}
src/main/resources/
└── application.yml                # all the knobs, every one annotated
```
