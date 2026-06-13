# 16 - DB Connection Leak (and the stack trace that saves you)

> A connection leak is a connection borrowed and never returned.
> The fix in your code is `try-with-resources`.
> The fix in production is `leakDetectionThreshold` — turn it ON.

## Thesis

You borrow a JDBC `Connection`. You forget to `close()` it. The pool
counts it as "active" forever. Repeat 10 times → pool is empty → every
subsequent request hangs at `connection-timeout` and then errors. The
service is down even though the database is idle.

HikariCP gives you exactly one diagnostic to find the bug:
**`leakDetectionThreshold`**. Set it (e.g. `4000`ms), and after that
window Hikari logs:

```
WARN  c.z.h.pool.ProxyLeakTask - Connection leak detection triggered for
   connection org.postgresql.jdbc.PgConnection@..., stack trace follows:
java.lang.Exception
    at com.zaxxer.hikari.pool.ProxyLeakTask.run(...)
    at com.claude.dbpoc.m16.service.LeakService.leak(LeakService.java:71)
    at com.claude.dbpoc.m16.web.LeakController.leak(LeakController.java:34)
    ...
```

That stack trace is the entire answer. It points at the exact line that
borrowed the connection. Without it, you'd be hunting for hours.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 16-db-connection-leak -am spring-boot:run
```

App boots on **:8216**. Pool config: `maximum-pool-size=10`,
`leak-detection-threshold=4000`, `connection-timeout=3000`.

## Run order

```bash
# 0. Baseline pool — should be active=0, idle=2 (minimumIdle).
curl 'localhost:8216/leak/stats' | jq

# 1. Leak 5 connections.
curl -X POST 'localhost:8216/leak/leak?count=5' | jq

# 2. Within ~4s, the application logs will scream with stack traces.
#    Re-check stats — active=5, idle=2 (replacement), total=7.
curl 'localhost:8216/leak/stats' | jq

# 3. Push the pool to exhaustion.
curl -X POST 'localhost:8216/leak/leak?count=20' | jq
#    Some attempts time out at connection-timeout (3s) with
#    "Connection is not available, request timed out".

# 4. Recovery — close all tracked-leak connections.
curl -X POST 'localhost:8216/leak/recover' | jq

# 5. Re-check stats — active drops back to near 0.
curl 'localhost:8216/leak/stats' | jq
```

## What each endpoint proves

### `POST /leak/leak?count=N`
Borrows `N` connections, runs a `SELECT 1` so Hikari counts them as
active, then **deliberately** drops them on the floor (held in a Map so
we can clean up later — in a real leak, the reference is just lost).

Within `leakDetectionThreshold` ms, Hikari fires `ProxyLeakTask` and logs
a stack trace pointing at this method. That's the diagnostic the module
is about.

### `GET /leak/stats`
The four pool counters that matter:

| Counter   | Meaning                                              |
|-----------|------------------------------------------------------|
| `active`  | Borrowed but not returned. The leak gauge.           |
| `idle`    | In pool, ready to lend.                              |
| `waiting` | Threads blocked in `getConnection()`. Trouble.       |
| `total`   | active + idle.                                       |

Healthy: `active` fluctuates with traffic, `idle ≥ minimumIdle`,
`waiting ≈ 0`. Leaking: `active` stair-steps **up** and never comes
down. Wire these to Prometheus.

### `POST /leak/recover`
Closes every connection we tracked. In a real leak you DO NOT have the
references — they were lost on the floor. Real recovery options:

1. **Deploy the patch** that closes properly (the actual fix).
2. **Restart the JVM** (drops the pool, frees server side too).
3. **`pg_terminate_backend(pid)`** server-side, after identifying the
   offending pids via `pg_stat_activity`.

## How not to leak in code

```java
// WRONG — manual borrow, no close.
public List<String> fetchNames() {
    Connection c = dataSource.getConnection();              // borrowed
    ResultSet rs = c.createStatement().executeQuery("...");
    // if anything throws here, c never closes → leak
    return materialize(rs);
}

// RIGHT — try-with-resources closes c on every path.
public List<String> fetchNames() {
    try (Connection c = dataSource.getConnection();
         Statement  s = c.createStatement();
         ResultSet  rs = s.executeQuery("...")) {
        return materialize(rs);
    }
}

// EVEN BETTER — let Spring own it.
public List<String> fetchNames() {
    return jdbcTemplate.queryForList("...", String.class);
}
```

The same applies to `EntityManager`s held outside a transaction, and
to any framework that exposes raw JDBC under the hood.

## The HikariCP knob explained

```yaml
spring:
  datasource:
    hikari:
      leak-detection-threshold: 4000    # ms — 0 disables
```

- **0** (default): disabled. No warning, ever.
- **2000–5000**: aggressive — catches almost everything, occasional
  false positives during legitimate slow queries. Right for non-prod.
- **5000–30000**: balanced. Right for production. Set above your
  slowest legitimate operation.
- **> 60000**: too lax — a leak will hide under "this query is slow".

The cost is **near zero**. Hikari schedules a `ScheduledExecutorService`
task per borrow; if you close in time it cancels, if not it logs. The
overhead is one task per checkout.

## Production checklist

| Symptom                                          | Likely cause                              | Fix                                                  |
|--------------------------------------------------|-------------------------------------------|------------------------------------------------------|
| `Connection is not available, request timed out` | Pool exhausted by leak                    | Set leakDetectionThreshold; grep logs for "leak detected". |
| `active = maximumPoolSize` and no traffic        | Slow but ongoing leak                     | Same. Stack trace IDs the line.                      |
| Service degrades after a deploy                  | The deploy introduced a new code path that doesn't close | Read the leak stack trace; close in finally/try-with-resources. |
| Restart "fixes" it for hours                     | Slow leak                                 | Restart is treating the symptom — find the borrow.   |
| Hikari logs nothing                              | leakDetectionThreshold = 0                | Set it to 5000ms.                                    |

## Why not just oversize the pool?

- A leak is **unbounded**. Doubling the pool doubles the time before
  the bug surfaces — it doesn't fix it.
- More connections cost the database too: per-backend memory, more
  context switches, more buffer-cache pressure. See
  [m15 - connection-pool](../15-db-connection-pool/) for sizing math.
- Oversizing masks the bug from your alarms. The leak still grows; you
  just don't notice until 3am.

## Files

```
src/main/java/com/claude/dbpoc/m16/
├── Application.java
├── service/
│   └── LeakService.java          # leak, stats, recover
└── web/
    └── LeakController.java       # /leak/{leak,stats,recover}
```

## Related modules

- **[m15 - connection-pool](../15-db-connection-pool/)** — sizing &
  knobs. Knowing your baseline `idle`/`active` matters for spotting
  leaks.
- **[m17 - pool-exhaustion](../17-db-pool-exhaustion/)** — the failure
  mode a leak eventually produces: cascading timeouts under load.
