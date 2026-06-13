# 13 - DB Optimistic vs Pessimistic vs CAS (the throughput crossover)

> Three strategies for "read $balance, +$1, write back" under concurrency.
> The right one depends entirely on your conflict rate. This module makes
> the crossover visible by running all three under the same harness.

## Thesis

Module 10 used `@Version` and `SELECT FOR UPDATE` as two of five lost-update
fixes. Module 11 documented the lock taxonomy. This module **measures** the
three strategies under controlled contention so the production decision
("which one do I pick?") becomes a number, not an argument.

| Strategy     | Mechanism                                            | Wins at                                |
|--------------|------------------------------------------------------|----------------------------------------|
| Optimistic   | `@Version` + retry on `ObjectOptimisticLockingFailureException` | **Low contention** — `<5%` conflict rate |
| Pessimistic  | `SELECT ... FOR UPDATE` inside the transaction       | **Hot rows** — high conflict rate      |
| CAS          | `UPDATE Account SET balance = balance + ? WHERE id = ?` | **Most workloads** when the new value is expressible in SQL |

The crossover: pessimistic queues callers cleanly, so on a single hot row
it wins. Optimistic burns CPU on retries when the conflict rate is high.
CAS skips the read-then-write entirely and wins whenever the new value is
a SQL function of the old one.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 13-db-optimistic-vs-pessimistic -am spring-boot:run
```

App boots on **:8213**.

## Run order

```bash
# 0. Seed 32 accounts at $0.00 / version=0.
curl -X POST 'localhost:8213/seed?accounts=32'

# 1. HOT-ROW (every thread hits id=1) — the contention regime.
curl 'localhost:8213/bench/optimistic?threads=16&iterations=200&hot=true'  | jq
curl 'localhost:8213/bench/pessimistic?threads=16&iterations=200&hot=true' | jq
curl 'localhost:8213/bench/cas?threads=16&iterations=200&hot=true'         | jq

# 2. DISPERSED (threads round-robin 1..32) — the low-contention regime.
curl -X POST 'localhost:8213/seed?accounts=32'
curl 'localhost:8213/bench/optimistic?threads=16&iterations=200&hot=false' | jq
curl 'localhost:8213/bench/pessimistic?threads=16&iterations=200&hot=false'| jq
curl 'localhost:8213/bench/cas?threads=16&iterations=200&hot=false'        | jq

# 3. All three in one call (convenience — balance drifts, see verdict).
curl -X POST 'localhost:8213/seed?accounts=32'
curl 'localhost:8213/bench/all?threads=16&iterations=200&hot=true'         | jq
```

## What each result tells you

Every response has the same shape:

```json
{
  "strategy": "optimistic",
  "threads": 16,
  "iterationsPerThread": 200,
  "hotRow": true,
  "accountCount": 32,
  "totalOps": 3198,
  "totalRetries": 2917,
  "elapsedMs": 4827,
  "opsPerSec": 662
}
```

- **`totalOps`**: successful +$1 increments. Should equal `threads * iterations`
  if no thread exhausted its retry budget.
- **`totalRetries`**: optimistic only. High here = high conflict rate.
- **`opsPerSec`**: the headline number for cross-strategy comparison.

### Hot-row expected shape (Postgres localhost, 16 threads × 200 iterations)

| Strategy     | totalRetries     | opsPerSec     | Verdict                          |
|--------------|------------------|---------------|----------------------------------|
| Optimistic   | huge (~3000)     | lowest        | Wasted CPU on retries.           |
| Pessimistic  | 0                | medium-high   | Clean FIFO queue, no retries.    |
| CAS          | 0                | **highest**   | Single statement, no read needed.|

### Dispersed expected shape

| Strategy     | totalRetries     | opsPerSec     | Verdict                          |
|--------------|------------------|---------------|----------------------------------|
| Optimistic   | ~0               | medium        | Cheap when conflict rate is low. |
| Pessimistic  | 0                | medium        | Wasted ceremony — lock uncontended. |
| CAS          | 0                | **highest**   | Always cheap when applicable.    |

If your numbers don't match the shape above, the most common cause is
p6spy logging at DEBUG — it adds 50–200µs per statement and totally
obscures the strategy differences. Set `logging.level.p6spy=WARN` in
`application.yml` before a serious bench.

## When CAS is *not* applicable

CAS only works when the new value is expressible as a SQL function of the
old one. Examples of business logic that breaks CAS:

- "Reject the increment if it would make the balance negative" — can be
  done as `WHERE balance + :delta >= 0` (rows-updated tells you success),
  but it scatters the rejection logic between SQL and application code.
- "Charge the user fee = max(0.1% × amount, $0.50), send confirmation
  email, then increment" — you need the old balance back in app code to
  compute the fee. Fall back to optimistic + retry, or pessimistic if the
  fee logic is heavy.
- "Compute the next value by calling an external pricing service." Same
  story: the new value isn't a function of the old in SQL.

## Production checklist

| Symptom                                              | Likely cause                                | Fix                                                  |
|------------------------------------------------------|---------------------------------------------|------------------------------------------------------|
| `ObjectOptimisticLockingFailureException` in logs    | Optimistic strategy, conflict rate too high | Switch to pessimistic or CAS; or add retry loop.    |
| Latency p99 climbs with traffic, throughput plateaus | Pessimistic on a hot row, queue grows       | Move work out of the locked region; or partition the row. |
| Balance "off by $X" intermittently                   | Lost update — none of the three was applied | Pick one. CAS is the cheapest; pessimistic the safest. |
| CPU pinned, ops/sec degrading                        | Optimistic retry storm                      | Cap MAX_RETRIES; or move to pessimistic at the hot row. |
| "I want it as fast as pessimistic but without locks" | Magical thinking                            | Use CAS. That's literally the answer.                |

## Files

```
src/main/java/com/claude/dbpoc/m13/
├── Application.java
├── TxConfig.java                  # TransactionTemplate bean
├── domain/
│   └── Account.java               # @Version Long, BigDecimal balance
├── repo/
│   └── AccountRepository.java     # findById, findByIdForUpdate, addToBalance (CAS)
├── service/
│   └── BenchService.java          # the three strategy benchmarks
└── web/
    ├── SeedController.java        # POST /seed
    └── BenchController.java       # /bench/{optimistic,pessimistic,cas,all}
```
