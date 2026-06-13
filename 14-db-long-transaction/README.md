# 14 - DB Long Transaction (silent killer)

> A long-running tx is the #1 cause of mysterious production DB degradation.
> It doesn't throw an exception. It doesn't show up in your app logs.
> It just makes the database slowly, quietly, irreversibly worse.

## Thesis

Four ways a long tx hurts production. None of them error. None of them
log. All of them are real.

| Effect              | Symptom you SEE                          | Mechanism                                        |
|---------------------|------------------------------------------|--------------------------------------------------|
| **Lock hold**       | p99 latency creeps up; pool saturates    | `FOR UPDATE` held; other writers queue          |
| **MVCC bloat**      | Table grows, queries slow, disk fills    | xmin horizon pinned, VACUUM can't reap dead rows|
| **Replication lag** | Replica falls behind primary             | WAL position held; replica can't replay past it |
| **Idle-in-tx**      | Connection pool exhausted with "active"  | Tx opened, never committed, holds locks + conn  |

The fix is structural: **short transactions**. Open late, commit early.
Never hold a tx across an external call (HTTP, queue publish, manual
review, S3 PUT). If you can't avoid it, run the work on a replica or as
out-of-band batch.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 14-db-long-transaction -am spring-boot:run
```

App boots on **:8214**.

## Run order

```bash
# 0. Seed 64 widgets.
curl -X POST 'localhost:8214/seed?count=64'

# 1. Lock-hold — T1 holds FOR UPDATE 5s; T2 waits ~5s.
curl 'localhost:8214/longtx/lock-hold?holdMs=5000' | jq

# 2. MVCC bloat — open long tx, do 200 updates, observe VACUUM can't reap.
curl 'localhost:8214/longtx/bloat?updates=200' | jq

# 3. Idle in transaction — see the row appear in pg_stat_activity.
curl 'localhost:8214/longtx/idle-in-transaction?idleMs=3000' | jq

# 4. Observability — find tx older than 1s (the 3am query).
curl 'localhost:8214/longtx/observability?minTxAgeMs=1000' | jq
```

## What each endpoint proves

### `/longtx/lock-hold`
T1 takes `SELECT ... FOR UPDATE` on widget#1 and holds for `holdMs`. T2
tries the same — it blocks the entire `holdMs`. The response reports
`t2WaitedForMs` ≈ `holdMs`.

There is **no error**, **no deadlock**, **no log**. T2's connection is
"active" from the application's point of view. In production this looks
like p99 latency spikes and connection pool saturation (see
[m17 - pool-exhaustion](../17-db-pool-exhaustion/)).

### `/longtx/bloat` ← the silent killer
The most dangerous variant. Steps:

1. **T1**: open a tx, take a snapshot (`SELECT 1`), then sit.
2. In parallel, run `updates` UPDATEs on widget#1. Each UPDATE
   creates a new tuple version; the old one becomes "dead".
3. `ANALYZE widget`, then `VACUUM widget`. VACUUM cannot reap any of
   the dead tuples — T1's snapshot horizon (`backend_xmin`) is older
   than them, so they might still be visible to T1.
4. Release T1 (commit).
5. `VACUUM widget` again. Now the dead tuples ARE reapable. `n_dead_tup`
   drops back to baseline.

The response compares `n_dead_tup` and table size at each step. The
diagnostic counter is `pg_stat_user_tables.n_dead_tup` — when it grows
without bound, you have a bloat problem.

In production, this is how a single **long analytics query**, a stuck
**background consumer**, or a **forgotten psql session** silently bloats
a hot table over hours. Days later you "suddenly" have query timeouts.

### `/longtx/idle-in-transaction`
The insidious variant: the tx is opened, did its work, but never
committed. The connection sits in `state='idle in transaction'` in
`pg_stat_activity`. It still holds:

- The connection (counts against pool max).
- Any row locks taken.
- The xmin horizon (blocks VACUUM).

The endpoint surfaces the row from `pg_stat_activity` so you can SEE the
state. In production, configure
`idle_in_transaction_session_timeout = '60s'` server-side as a backstop —
Spring will commit on `@Transactional` exit, but **manual
TransactionTemplate.execute** or **raw JDBC** can leak.

### `/longtx/observability` ← the 3am query
The query you paste into psql when production is slow:

```sql
SELECT
  pid,
  application_name,
  state,
  EXTRACT(EPOCH FROM (now() - xact_start)) * 1000 AS tx_age_ms,
  wait_event_type, wait_event,
  backend_xmin::text,
  LEFT(query, 200) AS query
FROM pg_stat_activity
WHERE datname = current_database()
  AND xact_start IS NOT NULL
  AND pid <> pg_backend_pid()
  AND EXTRACT(EPOCH FROM (now() - xact_start)) * 1000 >= :minMs
ORDER BY xact_start ASC;
```

Sort by `xact_start ASC` — the first row is the oldest tx, almost always
your culprit. The `backend_xmin` column shows the snapshot horizon this
tx is pinning. The `query` column is the trail back to the offending
code path.

Wire this as a scheduled check and alert on `tx_age_ms > 60_000` (1
minute) — most OLTP transactions should complete in tens of ms. If they
don't, something is wrong.

## The patterns to avoid

```java
// WRONG — tx held across an external HTTP call.
@Transactional
public Order createOrder(OrderRequest req) {
    Order o = orderRepo.save(toOrder(req));
    // BAD: external call inside the tx. If their service is slow,
    // your tx hangs and the row stays locked.
    fraudService.check(o);
    o.setStatus(Status.APPROVED);
    return o;
}

// RIGHT — split: short tx → external call → short tx.
public Order createOrder(OrderRequest req) {
    Order o = txTemplate.execute(s -> orderRepo.save(toOrder(req)));
    FraudVerdict v = fraudService.check(o);             // outside any tx
    return txTemplate.execute(s -> {
        Order fresh = orderRepo.findById(o.getId()).orElseThrow();
        fresh.setStatus(v.isOk() ? Status.APPROVED : Status.REJECTED);
        return fresh;
    });
}
```

```java
// WRONG — long iteration inside one tx.
@Transactional
public void backfill() {
    for (Long id : allIds()) {                          // could be millions
        update(id);                                      // one tx, growing forever
    }
}

// RIGHT — chunk into many short tx.
public void backfill() {
    List<Long> ids = allIds();
    for (List<Long> chunk : partition(ids, 1000)) {
        txTemplate.execute(s -> { chunk.forEach(this::update); return null; });
    }
}
```

## Production checklist

| Symptom                                       | Likely cause                                | Fix                                                  |
|-----------------------------------------------|---------------------------------------------|------------------------------------------------------|
| Table size grows, queries slowing             | Long tx pinning xmin, VACUUM can't keep up  | Find the long tx via /observability; shorten it.    |
| p99 latency creeping with no exceptions       | FOR UPDATE held across slow code            | Move slow code outside the tx.                       |
| Pool saturated, mostly "active" connections   | `idle in transaction` from manual TX leak   | Audit TransactionTemplate usage; set IIT timeout.    |
| Replica lag growing during peak               | Long tx on primary holding WAL              | Move long reads to a replica or out-of-band.         |
| VACUUM running constantly but bloat grows     | Multiple competing long tx                  | Reduce tx duration; raise autovacuum_naptime is NOT the fix. |

## Why "just buy more disk" isn't the fix

- Bloat doesn't just cost disk. It costs **buffer cache** (more pages to
  scan), **WAL bandwidth** (more dead tuples to log), and **index
  efficiency** (index entries point at dead tuples that have to be
  re-checked).
- A bloated 8GB table can be slower than a fresh 80GB one. The unit of
  performance is "live rows per page", not "total bytes".
- Long tx are usually a CODE bug (forgot to commit, held tx across an
  external call). Buying disk doesn't fix the bug.

## Files

```
src/main/java/com/claude/dbpoc/m14/
├── Application.java
├── TxConfig.java                  # TransactionTemplate bean
├── domain/
│   └── Widget.java                # just a row to update (creates dead tuples)
├── repo/
│   └── WidgetRepository.java      # findByIdForUpdate
├── service/
│   ├── Concurrency.java           # two-thread coordinator
│   └── LongTxService.java         # lockHold, bloat, idleInTransaction, observability
└── web/
    ├── SeedController.java        # POST /seed?count=N
    └── LongTxController.java      # /longtx/{lock-hold,bloat,idle-in-transaction,observability}
```
