# 10 - DB Isolation Levels (Anomalies and the Five Lost-Update Fixes)

> The fintech canary module. Two concurrent transfers, $100 starting
> balance, each adds $50. Expected $200. Observed under READ_COMMITTED:
> $150. Where did the other $50 go? It went into production.

## Thesis

You don't understand isolation levels until you've watched one thread eat
another thread's UPDATE in a debugger.

This module reproduces every classic isolation anomaly — dirty read,
non-repeatable read, phantom read, lost update — with deterministic
two-thread choreography. Each endpoint returns the observed numbers in
JSON so the anomaly is visible without attaching a debugger.

Then it shows the five production-grade fixes for the most expensive
anomaly (lost update), each behind its own endpoint, with the same
two-thread race.

Postgres specifics matter here. Postgres' implementation diverges from
the SQL standard:

- **READ_UNCOMMITTED** is silently upgraded to READ_COMMITTED. You
  cannot get a dirty read on Postgres.
- **REPEATABLE_READ** is snapshot isolation. The SQL standard allows
  phantoms; Postgres forbids them at this level. Stronger than spec.
- **SERIALIZABLE** is SSI (Serializable Snapshot Isolation). Detects
  read-write conflicts at commit time and aborts the loser with
  SQLSTATE 40001.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 10-db-isolation-levels -am spring-boot:run
```

App boots on **:8210**.

## Run order

```bash
# 0. Always seed first. Alice = $100, Bob = $200, version = 0.
curl -X POST 'localhost:8210/seed'

# 1. The four anomalies.
curl 'localhost:8210/anomaly/dirty-read'                                 | jq
curl 'localhost:8210/anomaly/non-repeatable-read?isolation=READ_COMMITTED'| jq
curl 'localhost:8210/anomaly/non-repeatable-read?isolation=REPEATABLE_READ'| jq
curl 'localhost:8210/anomaly/phantom-read?isolation=READ_COMMITTED'      | jq
curl 'localhost:8210/anomaly/phantom-read?isolation=REPEATABLE_READ'     | jq
curl 'localhost:8210/anomaly/lost-update'                                | jq   # ← the bug

# 2. Re-seed, then run each lost-update fix.
curl -X POST 'localhost:8210/seed'
curl 'localhost:8210/lost-update/select-for-update' | jq
curl 'localhost:8210/lost-update/optimistic'         | jq
curl 'localhost:8210/lost-update/cas-update'         | jq
curl 'localhost:8210/lost-update/retry'              | jq
curl 'localhost:8210/lost-update/serializable'       | jq

# 3. All five fixes in one call, each against a freshly-reset Alice.
curl 'localhost:8210/lost-update/all' | jq
```

## What each anomaly demo proves

### `/anomaly/dirty-read`
Even when we ask for `READ_UNCOMMITTED`, Postgres serves committed data.
The "dirty" balance T1 is holding is unreachable from T2. The endpoint
proves this empirically: T2's read returns the committed value, not
T1's `+999`. **Dirty reads are physically impossible on Postgres.**

### `/anomaly/non-repeatable-read`
T1 reads, T2 commits an update, T1 reads again.

- `READ_COMMITTED`: T1's two reads differ. Anomaly fires.
- `REPEATABLE_READ`: T1 sees the same value twice — the snapshot pinned
  at T1's start hides T2's commit.

### `/anomaly/phantom-read`
T1 runs `COUNT(*) WHERE amount > 100` twice with a matching INSERT in
between.

- `READ_COMMITTED`: counts diverge. Phantom fires.
- `REPEATABLE_READ`: snapshot hides the new row, counts match. **This
  is stronger than the SQL standard** — the spec allows phantoms at
  REPEATABLE_READ.

### `/anomaly/lost-update`  ← the headline bug
Two transfers, $100 → $200 expected → **$150 observed**.

Both transactions read $100, both compute $150, both UPDATE. The second
write clobbers the first. In production this is literal money
disappearing. The fixes below are the production-grade options.

## The five lost-update fixes

| Endpoint                               | Mechanism                                 | When to use                                  |
|----------------------------------------|-------------------------------------------|----------------------------------------------|
| `/lost-update/select-for-update`       | Pessimistic row lock (`SELECT FOR UPDATE`)| High-contention writes; you know there will be conflicts. |
| `/lost-update/optimistic`              | JPA `@Version` → conflict throws          | Low-conflict workloads; cheap until contention. |
| `/lost-update/cas-update`              | Single `UPDATE` w/ self-referencing SET   | Independent increments (counters, balances). Cheapest. |
| `/lost-update/retry`                   | Optimistic + bounded retry loop           | Web-scale workloads; latency over throughput. |
| `/lost-update/serializable`            | Postgres SSI (`40001` on conflict)        | When you want spec-correct semantics; tolerate higher abort rate. |

### `/lost-update/select-for-update`
T1's read holds a row-level lock. T2's read blocks at the FOR UPDATE
clause until T1 commits. T2's `+50` then runs against $150 → $200.

**Trade-off:** other FOR UPDATE callers block. Plain readers (MVCC) are
unaffected. Easiest to reason about; default choice for fintech.

### `/lost-update/optimistic`
Both transactions read with version `N`. Both compute `+50`. Both UPDATE
`... WHERE version = N`. The second one updates 0 rows; Hibernate throws
`ObjectOptimisticLockingFailureException`. **Without a retry, you've
traded silent loss for a 409.** That's still progress — silent loss is
the bug, the exception is the fix.

### `/lost-update/cas-update`
```sql
UPDATE account SET balance = balance + 50 WHERE id = ?
```
No read-then-write. Postgres serialises by row lock for the duration of
the single statement. Both UPDATEs succeed; final balance = $200.
**The cheapest correct fix** — but only works when the new value can be
expressed as a function of the old one in SQL. If you need
`balance > 0`-style preconditions or app-side logic, you need one of
the others.

### `/lost-update/retry`
Optimistic + a bounded retry loop. The thread that loses the optimistic
race re-reads, re-applies, re-writes. With a small backoff this is the
gold standard for low-conflict workloads — no blocking, no FOR UPDATE
hold time, just bounded retries.

**Trade-off:** the retry has to be idempotent. If the business logic
between the read and the write has side effects (notifications, audit
log inserts, external API calls), you must structure them so a retry
doesn't double-fire.

### `/lost-update/serializable`
`SERIALIZABLE` on Postgres = SSI. The DB detects the read-write
conflict at commit time and aborts one with SQLSTATE 40001. The caller
retries the whole transaction.

**Trade-off:** highest abort rate under contention. Use when you want
the strongest correctness guarantee and can absorb the retries. Don't
default to it as "the safe option" — under sustained contention it can
look like a deadlock storm to monitoring.

## Files

```
src/main/java/com/claude/dbpoc/m10/
├── Application.java
├── TxConfig.java                  # TransactionTemplate bean
├── domain/
│   ├── Account.java               # @Version Long, BigDecimal balance
│   └── Transfer.java              # phantom-read canary (table=transfer_log)
├── repo/
│   ├── AccountRepository.java     # findByIdForUpdate, findByIdForUpdateSkipLocked
│   └── TransferRepository.java
├── service/
│   ├── Concurrency.java           # two-thread coordination helper
│   ├── AnomalyService.java        # dirty/non-repeatable/phantom/lost-update demos
│   └── LostUpdateFixService.java  # the five fixes
└── web/
    ├── SeedController.java        # POST /seed
    ├── AnomalyController.java     # /anomaly/*
    └── LostUpdateController.java  # /lost-update/*
```

## Configuration that matters

```yaml
spring:
  jpa:
    properties:
      hibernate:
        connection:
          # Default for every connection. Demos override per call via
          # TransactionTemplate#setIsolationLevel.
          # 1=RU 2=RC 4=RR 8=SER
          isolation: 2
  datasource:
    hikari:
      # Multi-threaded demos with REQUIRES_NEW propagation can starve a
      # small pool when a transaction holds a lock. 20 leaves headroom.
      maximum-pool-size: 20
```

## Production checklist

| Symptom                                                | Likely cause                              | Fix                                                  |
|--------------------------------------------------------|-------------------------------------------|------------------------------------------------------|
| Account balance "off by $X" intermittently              | Lost update                               | One of the 5 fixes; default to `select-for-update` for balances. |
| `ObjectOptimisticLockingFailureException` in logs       | @Version conflict not retried             | Add retry loop with bounded budget + backoff.        |
| `SQLState 40001 serialization_failure` in logs          | SSI conflict at SERIALIZABLE              | Retry at the boundary — these are *expected* under SER. |
| Counters / balances trail real value under high QPS     | Read-then-write race                      | Replace with `UPDATE ... SET col = col + ?` (CAS).   |
| FOR UPDATE callers piling up                            | Lock held too long inside the transaction | Move I/O and external calls OUT of the locked region. |
