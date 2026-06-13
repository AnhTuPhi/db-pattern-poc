# 17 - DB Pool Exhaustion (cascading timeout, retry storm, bulkhead)

> One slow path can take down your entire service.
> Naive retries make it worse.
> Separate pools (bulkhead) makes the slow path fail without taking
> anything else with it.

## Thesis

A single pool means a single shared budget. When ONE code path runs
slow (long query, slow downstream, GC pause on the DB), it holds
connections. Other code paths queue. Within `connection-timeout` they
all start failing. The slow path has now killed the entire service.

The textbook reactive fix — **"add retry"** — makes it strictly worse:
each retry borrows again, which is exactly the resource the slow path
is hogging. Retries multiply pool pressure. This is the classic
"retries amplify outages" pattern.

The structural fix is **bulkheading**: give the risky code path its
OWN pool. When it saturates, it starves itself but nothing else. The
healthy traffic on the main pool is unaffected.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 17-db-pool-exhaustion -am spring-boot:run
```

App boots on **:8217**. Two Hikari pools:

| Pool        | maxPoolSize | connectionTimeout | Purpose                |
|-------------|-------------|-------------------|------------------------|
| `m17-main`  | 10          | 1000ms            | All "fast" traffic.    |
| `m17-slow`  | 4           | 1000ms            | The risky/slow path.   |

## Run order

```bash
# 0. Baseline.
curl 'localhost:8217/pool/stats' | jq

# 1. Cascade — fast requests time out because slow eats the pool.
curl 'localhost:8217/pool/cascade?slowCount=10&slowMs=4000&fastCount=20' | jq

# 2. Retry storm — naive retries make it 2-3x worse.
curl 'localhost:8217/pool/retry-storm?slowCount=10&slowMs=4000&fastCount=20' | jq

# 3. Bulkhead — slow goes to its own pool, main stays healthy.
curl 'localhost:8217/pool/bulkhead?slowCount=10&slowMs=4000&fastCount=20' | jq
```

## What each endpoint proves

### `/pool/cascade`
- Fires `slowCount=10` slow queries (`pg_sleep(4)` each) on the **main**
  pool (size 10). Pool is now fully active.
- Fires `fastCount=20` fast queries on the **same** main pool.
- Each fast query waits 1s (`connection-timeout`), can't get a
  connection, throws. Expected: `fastTimedOut ≈ 20`.

The fast queries are completely correct. They're failing because of an
unrelated slow path. **This is the production failure that takes the
whole service down.**

### `/pool/retry-storm`
Same setup, but each fast request is wrapped in `for (int a = 0; a < 3;)`
with no backoff. Total attempts shoots to ~60 for 20 requests.
`amplification` ≈ 3.0.

The retries don't help — they queue alongside the slow ones, sometimes
displacing legitimate work that would have succeeded. In production
this is what turns a 30-second blip into a 5-minute outage.

The lesson is general: **retries multiply load**. Always combine retries
with (a) exponential backoff with jitter, (b) a circuit breaker, (c) a
budget. None of those fix the underlying pool-exhaustion problem — they
just stop the retries from accelerating it.

### `/pool/bulkhead` ← the structural fix
Same load shape, but slow queries go to the **slow** pool (4 slots),
fast queries go to the **main** pool (10 slots, untouched).

Result: `slowFailed > 0` (slow pool saturated — by design),
`fastOk = fastCount` (main pool unaffected, all fast requests succeed).

The slow path **gracefully degrades** — when its bulkhead is full, new
slow requests fail fast instead of squatting on the main pool's
connections. The healthy traffic stays healthy.

## How to bulkhead in code

### Option A — separate DataSource per feature

```java
@Bean
@ConfigurationProperties("spring.datasource.main")
public HikariDataSource mainDs() { return new HikariDataSource(); }

@Bean
@ConfigurationProperties("spring.datasource.slow")
public HikariDataSource slowDs() { return new HikariDataSource(); }

@Bean
public JdbcTemplate slowJdbc(@Qualifier("slowDs") DataSource ds) {
    return new JdbcTemplate(ds);
}
```

```java
@Service
public class ReportService {
    private final JdbcTemplate slow;          // bulkheaded pool
    public ReportService(@Qualifier("slowJdbc") JdbcTemplate slow) { this.slow = slow; }
    public Report generateBigReport() { return slow.query("...", ...); }
}
```

### Option B — Semaphore per feature (same DataSource)

```java
private final Semaphore reportBulkhead = new Semaphore(4);

public Report generateBigReport() {
    if (!reportBulkhead.tryAcquire(100, MILLISECONDS)) {
        throw new BulkheadFullException();
    }
    try { return jdbc.query("...", ...); }
    finally { reportBulkhead.release(); }
}
```

Option B is lighter (one DataSource), Option A is stricter (the OS-level
sockets are physically separate). Use B for in-process isolation, A when
you also want to point the slow path at a replica or a different DB
user with different `statement_timeout`.

### Per-tenant bulkheading

Instead of "fast vs. slow", split by **tenant**. One noisy tenant can't
starve everyone else.

```java
Semaphore perTenant = bulkheads.computeIfAbsent(tenantId,
    k -> new Semaphore(maxConcurrentPerTenant));
```

This is how multi-tenant SaaS prevents the "loud customer breaks
quiet customer" failure.

## Production checklist

| Symptom                                            | Likely cause                                    | Fix                                                       |
|----------------------------------------------------|-------------------------------------------------|-----------------------------------------------------------|
| All endpoints timing out, but only some queries slow | One code path is saturating the shared pool   | Bulkhead the slow path (separate pool or semaphore).       |
| Outage gets WORSE after deploy of "retry"          | Retries amplify load                            | Backoff + jitter + circuit breaker; or remove the retry.   |
| One tenant brings down everyone                    | No per-tenant isolation                         | Per-tenant semaphore.                                      |
| `connection-timeout` errors during peak hours       | Pool too small for legitimate concurrency       | Read m15 — measure, don't guess sizing.                    |
| Pool size doubled, problem returned in a week      | Hiding a leak, not fixing it                    | Read m16 — turn on leakDetectionThreshold.                 |

## Why "just increase the pool" isn't the fix

- The pool isn't the bottleneck. The slow query is. Doubling the pool
  doubles the load on the DB; the DB then becomes the bottleneck.
- More connections cost the DB more memory (per-backend) and more
  scheduler overhead — past the optimum (see m15), latency gets
  WORSE.
- A bigger pool just delays the failure: 20 slow requests instead of 10
  saturate a pool of 20. The cascade returns at higher load.

The fix is to **constrain** the slow path, not feed it more.

## Files

```
src/main/java/com/claude/dbpoc/m17/
├── Application.java
├── PoolConfig.java              # @Bean mainDs + slowDs (separate Hikari pools)
├── service/
│   └── ExhaustionService.java   # cascade, retryStorm, bulkhead, stats
└── web/
    └── PoolController.java      # /pool/{cascade,retry-storm,bulkhead,stats}
```

## Related modules

- **[m15 - connection-pool](../15-db-connection-pool/)** — how to size
  the pools.
- **[m16 - connection-leak](../16-db-connection-leak/)** — the OTHER
  way pools exhaust.
- **[m14 - long-transaction](../14-db-long-transaction/)** — long tx
  is the most common reason a "fast" query suddenly becomes slow.
