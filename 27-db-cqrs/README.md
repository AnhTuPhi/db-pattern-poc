# 27 - DB CQRS (write model / read model / outbox sync)

> CQRS is "two databases for one entity".
> The hard part isn't deciding to split them. It's keeping them in
> sync without losing writes.

## Thesis

CQRS — **C**ommand-**Q**uery **R**esponsibility **S**egregation —
separates the model that takes writes from the model that serves
reads. The two have different schemas, different indexes, sometimes
different databases entirely.

Why? Because the shape of "give me this user's order summary" is
fundamentally different from the shape of "place an order". One wants
denormalized columns, full-text or geo indexes, maybe in Elasticsearch
or Redis. The other wants a normalized table with referential
integrity and tight write SLAs.

The two models stay in sync via **events**. After the write commits,
an event is published; a consumer projects it into the read model.
Crucially, you publish the event **atomically with the write**, not
after it — otherwise you have the classic "I wrote to the DB but my
Kafka producer crashed" problem.

The textbook tool for atomic publish: the **outbox pattern**.

```
   USER REQUEST
        │
        ▼
   ┌──────────┐  TRANSACTION:                       ┌─────────────┐
   │  WRITE   │  1. INSERT INTO orders ...          │   POSTGRES  │
   │  side    │  2. INSERT INTO outbox_events ...   │  write side │
   └──────────┘  COMMIT;                            └─────┬───────┘
                                                          │ poll
                                                          ▼
                                                   ┌────────────┐
                                                   │  POLLER    │  FOR UPDATE SKIP LOCKED
                                                   │  consumer  │  project into read model
                                                   └─────┬──────┘  mark processed_at
                                                          │
                                                          ▼
   ┌──────────┐                                    ┌─────────────┐
   │  READ    │  SELECT FROM user_order_summary    │   POSTGRES  │
   │  side    │  WHERE user_id = ?  (one row)      │  read side  │
   └──────────┘                                    └─────────────┘
```

The write tx is atomic: either both rows commit or neither does.
After commit, a poller drains the outbox in commit order and applies
each event to the read model. **At-least-once** delivery — the
projection MUST be idempotent.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 27-db-cqrs -am spring-boot:run
```

App boots on **:8227**. Schema `m27_cqrs`. Three tables:
`orders` (write), `outbox_events` (sync), `user_order_summary` (read).

## Run order

```bash
# 1. Place a few orders for user 7.
curl -X POST 'localhost:8227/cqrs/order?userId=7&amount=199.00' | jq
curl -X POST 'localhost:8227/cqrs/order?userId=7&amount=49.50'  | jq
curl -X POST 'localhost:8227/cqrs/order?userId=7&amount=12.30'  | jq

# 2. Read the summary from the READ model. May briefly say "no entry" —
#    the poller runs every second; within ~1s it'll catch up.
curl 'localhost:8227/cqrs/summary/7' | jq

# 3. Compare with the WRITE model (authoritative, more expensive).
curl 'localhost:8227/cqrs/raw/7' | jq

# 4. Cancel one of the orders → emits OrderCancelled.
curl -X POST 'localhost:8227/cqrs/order/2/cancel' | jq
sleep 1
curl 'localhost:8227/cqrs/summary/7' | jq
#    → order_count: 2,  total_revenue: 211.30   (199 - 49.5 cancelled doesn't fit demo math; numbers depend on what you cancelled)

# 5. Outbox backlog metric.
curl 'localhost:8227/cqrs/outbox' | jq

# 6. The replay magic — wipe the read model, re-process the outbox.
curl -X POST 'localhost:8227/cqrs/rebuild' | jq
sleep 2
curl 'localhost:8227/cqrs/summary/7' | jq
#    → same numbers as before. The read model is disposable.
```

## What each piece proves

### `placeOrder` — atomic write + publish

```java
@Transactional
public void placeOrder(...) {
    Order o = orders.save(new Order(...));
    jdbc.update("insert into outbox_events ...");  // SAME tx
}
```

Both rows commit together. If the tx rolls back (FK violation,
constraint failure, connection drop), neither write happened. There's
**no window in which the order exists but the event doesn't**.

This is what fixes the dual-write problem:

```
// BROKEN dual-write
orderRepo.save(o);          // commits at tx end
kafka.send(orderEvent);     // may fail; order is already committed
```

vs

```
// outbox
orderRepo.save(o);          // not yet committed
outboxRepo.save(event);     // not yet committed
// tx commits both together
// poller picks event up and forwards to Kafka in another tx
```

### `OutboxPoller` — drain in order, project idempotently

```sql
SELECT id, aggregate_id, event_type, payload
FROM outbox_events
WHERE processed_at IS NULL
ORDER BY id
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

- `ORDER BY id` — bigserial ids are commit-ordered, so we process
  events in the order they were emitted. Required because
  `OrderCancelled` would underflow the count if it ran before
  `OrderPlaced`.
- `FOR UPDATE SKIP LOCKED` — lets us run multiple poller instances
  safely. Each grabs a disjoint chunk. Postgres ≥ 9.5.
- After projection, `UPDATE ... SET processed_at = now()`. If we
  crash between project and update, the next poll re-runs the
  projection. That's why we need…

### Idempotency by `last_event_id`

Every read-model row tracks the highest outbox id it's seen:

```sql
INSERT INTO user_order_summary(user_id, ..., last_event_id)
VALUES (?, ..., ?)
ON CONFLICT (user_id) DO UPDATE SET
  order_count   = CASE WHEN excluded.last_event_id > user_order_summary.last_event_id
                       THEN user_order_summary.order_count + 1
                       ELSE user_order_summary.order_count END,
  ...
  last_event_id = greatest(user_order_summary.last_event_id, excluded.last_event_id);
```

A re-delivered event just bumps `last_event_id` to the same value and
re-no-ops the count delta. **The same event applied twice ≡ applied
once.**

### `rebuildReadModel` — disposable read model

```sql
TRUNCATE user_order_summary;
UPDATE outbox_events SET processed_at = NULL;
```

The next poll cycle rebuilds the read model from the outbox stream.
This is what makes CQRS+outbox so powerful operationally:

- Bug in projection logic? Fix it, replay, done.
- New field in the read model? Add the column, replay, done.
- Migrating to Elasticsearch? Wire up the new consumer, replay.

**Your read model is cattle, not pets.**

## What's the read model, really?

In this POC the read model is **another Postgres table** in the same
DB. That's a valid CQRS topology — "one DB, two schemas". You get the
separation of read/write models without the operational cost of
running a second store.

Real-world read models often live elsewhere:

| Read store     | When                                         |
|----------------|----------------------------------------------|
| Postgres table | Same DB, eventual consistency OK, no full-text needed. |
| **Redis**      | Hot key-value reads at scale. TTL or unbounded. |
| **Elasticsearch** | Full-text search, faceted filters, log-shape data. |
| **ClickHouse** | Analytics over big aggregates, OLAP. |
| **Materialized view** | Aggregate-shaped read inside Postgres (m26 territory). |

The pattern is identical — `outbox → poller → upsert into store X`.
Swap the store, keep the wiring.

## At-least-once is not at-most-once

A poller crash between "project" and "mark processed_at" causes the
event to be re-delivered. This is normal and acceptable, **as long as
the projection is idempotent**. The two failure modes you must engineer
around:

1. **Replay the same event.** Idempotency key (`last_event_id` here).
2. **Replay events in the wrong order.** Caused by parallel
   projection on the same aggregate; serialized by sorting on the
   aggregate id and processing per-aggregate or by reading single
   threaded.

What you usually don't get: **exactly-once delivery**. There's no
cheap way. Two-generals problem.

## CQRS without outbox (and why you don't want it)

```java
@Transactional
public void placeOrder(...) {
    orderRepo.save(o);
}
// after tx commits:
eventBus.publish(orderEvent);   // ← dual write
```

When `publish` fails (Kafka unreachable, OOM, process restart): the
order is committed, the event never reached the consumer, the read
model is permanently behind. Every CQRS system that tried this
re-discovered the outbox pattern within a year.

**Don't dual-write.** Outbox, transactional log tailing (Debezium /
logical replication), or change-data-capture. Pick one; commit to it.

## Production checklist

| Symptom                                        | Likely cause                            | Fix                                                              |
|------------------------------------------------|-----------------------------------------|------------------------------------------------------------------|
| Read model behind by hours                     | Poller crashed and didn't restart       | Health-check the poller; alert on outbox backlog.                |
| Read model has wrong totals                    | Projection bug → replayed events double-counted | Add idempotency key; rebuild.                              |
| Outbox table is huge                           | Old events never deleted                | Vacuum / DELETE WHERE processed_at < now() - retention.          |
| Read model totals different from write model   | Projection logic forgot an event type   | Add the handler; rebuild.                                        |
| One slow event blocks the rest                 | No skip-failed-event logic              | Bump attempts; alert on attempts > N; quarantine and continue.   |
| Outbox events out of order                     | Concurrent appenders different aggregates ok; same aggregate not | Keep ORDER BY id; sequence within an aggregate. |
| Two pollers racing                             | No SKIP LOCKED                          | Add `FOR UPDATE SKIP LOCKED` to the SELECT.                      |
| Reads see stale data immediately after write   | Eventual consistency                    | Expected; for read-your-writes, route THAT read at the write model (m21 pattern). |

## Files

```
src/main/java/com/claude/dbpoc/m27/
├── Application.java                       # @EnableScheduling
├── domain/Order.java                      # write model entity
├── repo/OrderRepository.java
├── service/
│   ├── WriteSideService.java              # placeOrder, cancelOrder (atomic + outbox append)
│   ├── OutboxPoller.java                  # @Scheduled drain, SKIP LOCKED
│   └── ReadSideService.java               # summary from read model, backlog, rebuild
└── web/
    └── CqrsController.java                # /cqrs/{order,summary,raw,outbox,rebuild,poll-now}
src/main/resources/
├── application.yml                        # port 8227, outbox.poll-interval-ms
└── schema.sql                             # orders + outbox_events + user_order_summary
```

## Related modules

- **[m21 - read-replica](../21-db-read-replica/)** — the simplest
  read/write split — same model, different physical store.
- **[m22 - table-partitioning](../22-db-table-partitioning/)** —
  outbox table almost always wants partitioning by created_at for
  cheap retention.
- **[m23 - sharding](../23-db-sharding/)** + CQRS: cross-shard reads
  are easy when the read model is in a single shared store.
- **[m25 - caching-layers](../25-db-caching-layers/)** — Redis as a
  read model is one step up from Redis as a cache.
- **[m26 - materialized-view](../26-db-materialized-view/)** — when
  the read model lives in the same Postgres, the MV is the simpler
  story; outbox is needed when it leaves.
- **[m28 - jsonb](../28-db-jsonb/)** — outbox payloads are JSONB
  in practice. m28 covers the GIN-index ergonomics.
