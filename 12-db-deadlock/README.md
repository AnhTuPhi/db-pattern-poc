# 12 - DB Deadlock (the wait-for cycle, and the structural fix)

> Deadlock is not a database bug. The database is catching YOUR bug.
> The fix is almost never "retry harder" — it is **lock-ordering**.

## Thesis

Two transactions, two locks, opposite acquisition order:

```
T1: lock A → lock B
T2: lock B → lock A
```

T1 holds A, waits on B. T2 holds B, waits on A. Cycle. Postgres' detector
fires after `deadlock_timeout` (default 1s) and aborts one with SQLSTATE
**40P01** (`deadlock_detected`). The aborted transaction's caller must
retry or fail.

The signal is 40P01. The bug is the **cycle**. Remove the cycle and you
remove the deadlock structurally — no retries needed.

This module reproduces the textbook race, dumps Postgres' wait-for graph
mid-deadlock, and shows the lock-ordering fix and the retry-at-boundary
fallback for the cases you can't structurally fix.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 12-db-deadlock -am spring-boot:run
```

App boots on **:8212**.

## Run order

```bash
# 0. Seed alice + bob with $1000 each.
curl -X POST 'localhost:8212/seed'

# 1. Reproduce — expect 40P01 on one side, ~1s elapsed (deadlock_timeout).
curl 'localhost:8212/deadlock/reproduce' | jq

# 2. Wait-for graph during the race — pg_locks + pg_blocking_pids.
curl -X POST 'localhost:8212/seed'
curl 'localhost:8212/deadlock/graph'     | jq

# 3. The FIX — canonical lock-ordering, both commit, no deadlock.
curl -X POST 'localhost:8212/seed'
curl 'localhost:8212/deadlock/lock-ordering' | jq

# 4. Fallback — leave the buggy ordering, wrap in a retry loop.
curl -X POST 'localhost:8212/seed'
curl 'localhost:8212/deadlock/retry' | jq
```

## What each endpoint proves

### `/deadlock/reproduce`
T1 takes A, holds 80ms, attempts B. T2 takes B, holds 80ms, attempts A.
After `deadlock_timeout` (~1s on default Postgres), the detector fires and
aborts one with 40P01. The response identifies which side lost and reports
the elapsed time.

The exception chain wraps a `PSQLException` with SQLState `40P01`. Spring
sometimes translates this to `CannotAcquireLockException` — both contain
the original SQLState in `getMessage()`, which is how the demo identifies
"this was a deadlock, not some other lock failure".

### `/deadlock/graph` ← the 3am psql query
While the race is in progress, a third connection queries:

```sql
SELECT
  a.pid, a.application_name, a.state, a.wait_event_type, a.wait_event,
  l.locktype, l.mode, l.granted,
  l.relation::regclass::text AS relation,
  pg_blocking_pids(a.pid)     AS blocked_by,
  left(a.query, 120)          AS query
FROM pg_locks l
JOIN pg_stat_activity a ON a.pid = l.pid
WHERE (l.relation = 'account'::regclass OR l.locktype IN ('transactionid','tuple'))
  AND a.pid <> pg_backend_pid()
ORDER BY a.pid, l.granted DESC;
```

In the response, each row is one held-or-requested lock:
- `granted=true` → it's the holder.
- `granted=false` → it's a waiter, and `blockedBy` lists the PIDs whose
  locks it's queued behind.

A **cycle in `blockedBy` between two PIDs** is the deadlock.

The `query` column is the SQL each backend is currently executing —
that's the trail back to the application code path. In production, log it
alongside the 40P01 retry so you can attribute the bug.

### `/deadlock/lock-ordering` ← the structural fix
Same workload, but both transactions always lock `min(id)` first:

```java
Long firstLock  = Math.min(from, to);
Long secondLock = Math.max(from, to);
Account a1 = accountRepo.findByIdForUpdate(firstLock).orElseThrow();
Account a2 = accountRepo.findByIdForUpdate(secondLock).orElseThrow();
// ... do the transfer using src/dst, which may be a1 or a2.
```

The business direction (`from` → `to`) is decoupled from the lock
acquisition order. The cycle is **structurally impossible**: both
transactions try for the same row first; one wins, the other waits, both
eventually commit.

The invariant: **any time you take >1 row lock in one tx, sort the keys
before acquiring**. This is reviewable at the function boundary — you
don't have to think about all callers.

### `/deadlock/retry`
Same buggy ordering as `/reproduce`, but wrapped in a retry loop that
catches 40P01, backs off with jitter, and retries up to 5 times. Most
calls will succeed on the second attempt because the second attempt finds
no waiter on the other side.

**Use this only when you can't structurally fix the ordering** (e.g. you
don't own the inner code, or there are non-row locks involved). Under
sustained contention the retries saturate the wait-for graph and look
like a DoS to monitoring.

## The lock-ordering invariant in production code

```java
// WRONG — locks in business direction.
public void transfer(Long fromId, Long toId, BigDecimal amount) {
    Account src = repo.findByIdForUpdate(fromId).orElseThrow();
    Account dst = repo.findByIdForUpdate(toId).orElseThrow();
    src.setBalance(src.getBalance().subtract(amount));
    dst.setBalance(dst.getBalance().add(amount));
}

// RIGHT — sort the ids before acquiring; business direction is post-lock.
public void transfer(Long fromId, Long toId, BigDecimal amount) {
    Long first  = Math.min(fromId, toId);
    Long second = Math.max(fromId, toId);
    Account a1 = repo.findByIdForUpdate(first).orElseThrow();
    Account a2 = repo.findByIdForUpdate(second).orElseThrow();
    Account src = a1.getId().equals(fromId) ? a1 : a2;
    Account dst = a1.getId().equals(toId)   ? a1 : a2;
    src.setBalance(src.getBalance().subtract(amount));
    dst.setBalance(dst.getBalance().add(amount));
}
```

The same pattern generalises to N locks: collect the keys, sort them, then
acquire in order. Resist the temptation to "just lock both with a single
range query" — that only works if your business logic is symmetric in
the locked rows.

## Production checklist

| Symptom                                              | Likely cause                                | Fix                                                  |
|------------------------------------------------------|---------------------------------------------|------------------------------------------------------|
| `SQLState 40P01 deadlock_detected` in logs           | Two code paths take locks in opposite order | Lock-ordering invariant on the multi-lock function. |
| Deadlock cluster on a single FK-cascading parent     | Parent row locked by a child INSERT path    | Take the parent lock at the START of the tx (canonical order). |
| Deadlock between a worker job and the API           | Two ownership rules of the same row         | Move the worker's update behind the API's lock-ordering invariant. |
| Retry storm on 40P01                                 | Retry without jitter, sustained contention  | Add jitter to backoff; better: structural fix.       |
| `deadlock_timeout` blows latency p99                 | Too many concurrent two-lock transactions   | Audit lock-ordering on hot paths; the timeout is the SYMPTOM. |

## Why retry isn't the fix

- Retry wastes CPU. Each retry re-acquires both locks, possibly causing another deadlock.
- Retry doesn't fix the root cause. The wait-for graph still has a cycle waiting to be drawn.
- Retry shifts the failure from "deadlock detected" to "deadlock storm" — same outcome, harder to diagnose.

Lock-ordering is a property of the **code**, not the runtime. You can
audit it by reading the function. Retries hide the audit.

## Files

```
src/main/java/com/claude/dbpoc/m12/
├── Application.java
├── TxConfig.java                  # TransactionTemplate bean
├── domain/
│   └── Account.java               # alice + bob, no @Version (locks here are explicit)
├── repo/
│   └── AccountRepository.java     # findByIdForUpdate
├── service/
│   ├── Concurrency.java           # two-thread coordinator
│   └── DeadlockService.java       # reproduce, graph, lock-ordering, retry
└── web/
    ├── SeedController.java        # POST /seed
    └── DeadlockController.java    # /deadlock/{reproduce,graph,lock-ordering,retry}
```
