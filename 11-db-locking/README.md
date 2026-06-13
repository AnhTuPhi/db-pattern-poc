# 11 - DB Locking (FOR UPDATE / SKIP LOCKED / NOWAIT / table lock)

> SELECT FOR UPDATE has three production-grade variants and most engineers
> only know one. The one you don't know — `SKIP LOCKED` — is also the
> cheapest correct way to build a job queue without Redis, Kafka, or
> Zookeeper. This module is about the other two.

## Thesis

Module 10 used `SELECT FOR UPDATE` as one of five lost-update fixes. This
module is the full taxonomy: the variants Postgres actually offers, what
each one does to a waiting transaction, and the canonical workloads each
one was designed for.

| Primitive                                | Behaviour                             | Production use                                   |
|------------------------------------------|---------------------------------------|--------------------------------------------------|
| `SELECT ... FOR UPDATE`                  | Wait until the holder commits         | Default. Queue callers behind a contended row.   |
| `SELECT ... FOR UPDATE SKIP LOCKED`      | Skip locked rows, return the rest     | Job queues — fan out work over N workers.        |
| `SELECT ... FOR UPDATE NOWAIT`           | Fail immediately if any row is locked | REST handlers — fail-fast over piled-up waiters. |
| `LOCK TABLE ... IN EXCLUSIVE MODE`       | Block every reader and writer         | DDL-adjacent maintenance only.                   |
| `pg_locks` + `pg_stat_activity`          | Read lock graph                       | "Who is locking whom" diagnosis.                 |

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 11-db-locking -am spring-boot:run
```

App boots on **:8211**.

## Run order

```bash
# 0. Always seed first — Alice (1 row) + 50 PENDING jobs.
curl -X POST 'localhost:8211/seed'

# 1. Plain FOR UPDATE — T2's wait time should be ~300ms.
curl 'localhost:8211/locks/for-update' | jq

# 2. SKIP LOCKED — 5 workers × 10 jobs each, zero overlap.
curl -X POST 'localhost:8211/seed?jobs=50'
curl 'localhost:8211/locks/skip-locked?workers=5&perWorker=10' | jq

# 3. NOWAIT — T2 fails in <50ms with SQLSTATE 55P03.
curl 'localhost:8211/locks/nowait' | jq

# 4. Table-level EXCLUSIVE — plain SELECT blocked too.
curl 'localhost:8211/locks/table' | jq

# 5. Observability — pg_locks snapshot while T1 holds.
curl 'localhost:8211/locks/observability' | jq
```

## What each primitive proves

### `/locks/for-update`
T1 takes the row lock and holds it for 300ms. T2 issues the same FOR UPDATE
and **blocks inside Postgres** until T1 commits. The response reports T2's
elapsed time (~300ms) as proof the wait happened.

**Production use:** the default when you know there will be contention and
you want callers to queue rather than fail. Drawback: if T1 hangs (slow
external call inside the transaction), every waiter on that row is hung too.
Keep I/O OUT of the locked region.

### `/locks/skip-locked` ← the headline pattern
N worker threads simultaneously run:
```sql
SELECT * FROM job WHERE status='PENDING' ORDER BY id LIMIT M FOR UPDATE SKIP LOCKED
```
Because of `SKIP LOCKED`, no two workers ever see the same row, and nobody
waits. The response reports `perWorkerIds` (per-worker disjoint claim sets)
and `overlapCount` (which should be exactly 0).

**Production use:** the cheapest correct way to fan out a stream of
independent work over stateless workers. The DB is the coordinator. Used by
Sidekiq-pg, GoodJob, Faktory, Oban, etc.

**Catches:**
- Long-running jobs hold the row lock for the whole tx. Move I/O OUT of the tx.
- Worker crash → row goes back to PENDING. Make job logic **idempotent**.
- `ORDER BY id` for FIFO; `ORDER BY priority DESC, id ASC` for priority queues.

### `/locks/nowait`
T1 holds the lock. T2 issues `FOR UPDATE NOWAIT` and **fails in microseconds**
with `PSQLException` whose SQLState is `55P03 (lock_not_available)`. The
response reports T2's elapsed time (typically <50ms) as proof of fail-fast.

**Production use:** REST endpoints that perform money transfers. Better to
return 409 immediately and let the client retry than tie up a request
thread waiting for a busy row. The 409 has bounded latency; the wait
doesn't.

### `/locks/table`
T1 issues `LOCK TABLE account IN EXCLUSIVE MODE` and holds for 200ms. T2's
**plain SELECT** blocks until T1 commits, because EXCLUSIVE MODE conflicts
with everything except `ACCESS SHARE`.

**Production use:** almost never on online traffic. Legitimate cases:
- One-off DDL-adjacent maintenance ("nobody touch this table while I
  rebuild this index by hand").
- Coordinating a singleton background job that absolutely must not race.

### `/locks/observability`
While T1 holds a row lock, a separate connection queries `pg_locks` joined
with `pg_stat_activity` and `pg_blocking_pids()`. The result is the
canonical lock graph you'd ship to monitoring or paste into an incident
channel.

The same query, on a real database:
```sql
SELECT a.pid, a.application_name, a.state, a.wait_event_type, a.wait_event,
       l.locktype, l.mode, l.granted,
       l.relation::regclass::text AS relation,
       pg_blocking_pids(a.pid) AS blocked_by,
       left(a.query, 120) AS query
FROM pg_locks l
JOIN pg_stat_activity a ON a.pid = l.pid
WHERE NOT a.pid = pg_backend_pid()
ORDER BY a.pid, l.granted DESC;
```

## Production checklist

| Symptom                                              | Likely cause                              | Fix                                                  |
|------------------------------------------------------|-------------------------------------------|------------------------------------------------------|
| Request threads pile up, latency climbs              | FOR UPDATE waiters behind a slow holder   | Move I/O out of the locked region; or switch to NOWAIT + retry. |
| Worker crashes leave "stuck PENDING" job rows        | Mistook lockedBy column for the lock      | The row-level `FOR UPDATE` is the lock; lockedBy is advisory. |
| Two workers processed the same job                   | SKIP LOCKED query was wrong               | Verify the dequeue is one tx with `FOR UPDATE SKIP LOCKED` AND the status update commits within it. |
| `could not obtain lock` in app logs                  | NOWAIT fired                              | Retry with backoff at the boundary; that's the design. |
| Online queries blocked by a single backend           | Someone took LOCK TABLE EXCLUSIVE         | `pg_terminate_backend(pid)` carefully; review the cron / migration that did it. |

## Files

```
src/main/java/com/claude/dbpoc/m11/
├── Application.java
├── TxConfig.java                  # TransactionTemplate bean
├── domain/
│   ├── Account.java               # Alice's row — FOR UPDATE / NOWAIT target
│   └── Job.java                   # PENDING/RUNNING/DONE — SKIP LOCKED queue
├── repo/
│   ├── AccountRepository.java     # findByIdForUpdate, findByIdForUpdateNowait
│   └── JobRepository.java         # claimPending (SKIP LOCKED), claimPendingNowait
├── service/
│   ├── Concurrency.java           # two-thread coordination helper
│   └── LockingService.java        # the five primitives
└── web/
    ├── SeedController.java        # POST /seed
    └── LockingController.java     # /locks/*
```
