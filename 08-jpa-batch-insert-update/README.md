# 08 - JPA Batch Insert / Update (Why your bulk write is 50× slower than it should be)

> The one-liner answer to "why is `saveAll(10_000)` taking 12 seconds?" is
> never "because JPA is slow" — it's "because at least one of four knobs
> isn't set". This module isolates those knobs and shows the cost of each.

## Thesis

JPA bulk-write performance is a function of four orthogonal things:

1. **`hibernate.jdbc.batch_size`** — defaults to 0 (batching off). Every INSERT
   is its own round-trip until you set this.
2. **Identity strategy.** `IDENTITY` disables JDBC batching on Postgres /
   MySQL — Hibernate has to round-trip after each INSERT to read back the
   generated PK. `SEQUENCE` (with `allocationSize >= batch_size`) and
   application-assigned PKs do not.
3. **`hibernate.order_inserts` / `order_updates`** — without these, every
   "switch from Customer to Order and back" resets the batch.
4. **Driver-level rewrite.** Postgres needs `?reWriteBatchedInserts=true`
   or "batching" still sends N PreparedStatement executions over the wire.

This module runs all seven variants under one endpoint and returns a
sorted JSON table so you can read the cost of each off the data.

## Setup

```bash
cd ..
docker compose --profile postgres up -d                # default
# docker compose --profile oracle  up -d               # for the oracle profile
./mvnw -pl 08-jpa-batch-insert-update -am spring-boot:run
```

App boots on **:8208**.

## Run order

```bash
# 1. The headline endpoint — every INSERT variant, sorted by elapsedMs.
curl 'localhost:8208/bench?n=10000' | jq

# 2. Individual variants (use ?n=N to size, default 10,000):
curl 'localhost:8208/bench/jdbc'              | jq   # the floor
curl 'localhost:8208/bench/identity'          | jq   # the anti-pattern
curl 'localhost:8208/bench/sequence'          | jq   # the win
curl 'localhost:8208/bench/sequence-100'      | jq   # allocationSize=100
curl 'localhost:8208/bench/assigned'          | jq   # UUID PK
curl 'localhost:8208/bench/sequence-unbatched'| jq   # SEQUENCE w/ batching off
curl 'localhost:8208/bench/ordered-inserts'   | jq   # interleaved entities

# 3. Seed for the UPDATE-side bench (writes 10k SequenceCustomer rows).
curl -X POST 'localhost:8208/seed?customers=10000'

# 4. UPDATE strategies (run each, compare sqlStatements).
curl 'localhost:8208/update/bulk?n=2000&approach=per-entity'        | jq
curl 'localhost:8208/update/bulk?n=2000&approach=in-clause'         | jq
curl 'localhost:8208/update/bulk?approach=update-query&country=US'  | jq
```

## Expected ranking (Postgres, n=10000)

| Variant                     | SQL count           | Elapsed (typical)  | Why                                                  |
|-----------------------------|---------------------|--------------------|------------------------------------------------------|
| `jdbc-baseline`             | ~200 (10k / 50)     | fastest            | No persistence context. The floor.                   |
| `assigned-uuid`             | ~200                | very close to jdbc | UUID PK skips IDENTITY / sequence entirely.          |
| `sequence-batch50-alloc100` | ~100 + 100 seq      | very close         | Half the sequence hops vs alloc=50.                  |
| `sequence-batch50`          | ~200 + 200 seq      | very close         | One sequence hop per batch — the recommended shape.  |
| `mixed-ordered-inserts`     | ~400 (2 types × 200)| ~2× single-type    | Same per-type, doubled by two entity kinds.          |
| `sequence-unbatched`        | ~10000              | 30-50× slower      | Same entity, batching off. Proves the knob matters.  |
| `identity`                  | ~10000              | 30-50× slower      | IDENTITY disables JDBC batching. The anti-pattern.   |

`sequence-batch50` and `assigned-uuid` are typically within a few percent of
the raw `jdbc-baseline` — proof that JPA per se is not the problem.

## What each variant proves

### `jdbc-baseline`
`JdbcTemplate.batchUpdate` with `gen_random_uuid()::text`. No persistence
context, no dirty-check, no flush. This is the floor. If a JPA variant is
within 2× of this number, JPA is not the bottleneck.

### `identity`
`@GeneratedValue(strategy = IDENTITY)`. Hibernate logs `HHH000069: disabling
insert batching` at startup. Even with `batch_size=50`, you get N statements.
The fix is to not use IDENTITY for entities you bulk-insert.

### `sequence-unbatched`
Same `SequenceCustomer` entity as the winning variant, but we flush after
every `save()`. Demonstrates that **the entity is not the problem** — the
batching pipeline is. Same data, batching turned off by hand, ~50× slower.

### `sequence-batch50`
The recommended shape. `allocationSize=50` matches `jdbc.batch_size=50`.
One sequence hop per batch, one batched INSERT per 50 rows. JDBC batching
is on the wire (Postgres' `reWriteBatchedInserts=true` is the make-or-break
piece — without it, "batched" inserts are still N wire packets, just sent
without waiting for the ack).

### `sequence-batch50-alloc100`
`allocationSize=100` with `batch_size=50` means one sequence hop per *two*
batches. Saves a round-trip per 100 rows. On a fast LAN this is in the
noise; on Oracle RAC where the sequence is a clustered coordinator, it's
measurable.

### `assigned-uuid`
Application-assigned UUID. Most DB-agnostic answer — no `IDENTITY`, no
sequence, batching just works on every database. The catch is the PK is 16
bytes (vs 8 for `BIGINT`) and random UUIDv4 hurts B-tree insert locality.
For high-throughput inserts use UUIDv7 or ULID; for sharded systems the
trade-off is usually worth it.

### `mixed-ordered-inserts`
Interleaves `SequenceCustomer` and `CustomerOrder` saves. With
`hibernate.order_inserts=true`, Hibernate groups by entity type at flush
time — so the wire sees two batches per flush window instead of one batch
per type-switch. Turn `order_inserts` off and rerun to feel the difference;
the elapsed time roughly doubles.

## UPDATE-side variants (`/update/bulk`)

| `approach`        | What it does                                                | SQL statements        |
|-------------------|-------------------------------------------------------------|-----------------------|
| `per-entity`      | Load N managed entities, mutate each, auto-flush UPDATEs.   | ~N/batch_size batches |
| `in-clause`       | `UPDATE ... WHERE id IN (:ids)` — one statement.            | 1                     |
| `update-query`    | `UPDATE ... WHERE country = :country` — one statement.      | 1                     |

The `per-entity` approach pays the **dirty-check cost** on every flush
(see module 07). For ad-hoc admin endpoints that's fine. For batch jobs,
the JPQL UPDATE wins by a long way.

## Configuration that matters

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50              # The headline knob.
          batch_versioned_data: true  # Required for @Version entities to batch.
        order_inserts: true           # Group INSERTs by entity type at flush.
        order_updates: true           # Same for UPDATEs.

  datasource:
    # Postgres-specific. Without this the "batches" are still N round-trips.
    url: jdbc:postgresql://...?reWriteBatchedInserts=true
```

## Files

```
src/main/java/com/claude/dbpoc/m08/
├── Application.java
├── DataSourceConfig.java          # SqlCounter wrapping via BeanPostProcessor
├── BatchSettings.java             # Reflects active config back to the JSON response
├── domain/
│   ├── IdentityCustomer.java      # IDENTITY — the anti-pattern
│   ├── SequenceCustomer.java      # SEQUENCE allocationSize=50 — the win
│   ├── SequenceCustomer100.java   # SEQUENCE allocationSize=100 — fewer sequence hops
│   ├── AssignedCustomer.java      # UUID PK — DB-agnostic
│   └── CustomerOrder.java         # Second entity for the ordering demo
├── repo/                          # Spring Data repos for each entity
├── BenchService.java              # The 7 INSERT variants
├── BenchController.java           # /bench  ← headline
├── SeedController.java            # POST /seed?customers=N
└── UpdateController.java          # /update/bulk?approach=...
```

## Oracle profile

```bash
docker compose --profile oracle up -d
./mvnw -pl 08-jpa-batch-insert-update spring-boot:run -Dspring-boot.run.profiles=oracle
```

The Oracle profile drops `reWriteBatchedInserts` (Oracle does proper
batching natively) but otherwise behaves the same. The sequence story is
where Oracle is most interesting: `NOCACHE` sequences on RAC chat with the
global coordinator on every allocation, so `allocationSize=100` is
genuinely 100× cheaper than `allocationSize=1`.

## Production checklist

| Smell                                            | Fix                                                        |
|--------------------------------------------------|------------------------------------------------------------|
| `saveAll(largeList)` takes seconds               | Set `hibernate.jdbc.batch_size`. Check the strategy.       |
| Entity uses `GenerationType.IDENTITY`            | Switch to `SEQUENCE` for entities you bulk-insert.         |
| Postgres + JPA + slow bulk insert                | Add `?reWriteBatchedInserts=true` to the JDBC URL.         |
| Entity has `@Version` and inserts don't batch    | Set `hibernate.jdbc.batch_versioned_data=true`.            |
| Service interleaves saves of multiple entity types | Enable `hibernate.order_inserts` / `order_updates`.      |
| Bulk UPDATE that scans the whole table in code   | Replace with one JPQL `@Modifying` `UPDATE` statement.     |
