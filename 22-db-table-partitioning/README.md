# 22 - DB Table Partitioning (RANGE / LIST / HASH + sliding window)

> Partitioning is a query-shape commitment.
> Pick the wrong key and you get worse performance, not better.

## Thesis

Partitioning is **physical** in Postgres: each child partition is its own
table file on disk. The parent is metadata only. The planner uses the
WHERE clause to **prune** partitions before scanning. Done right, a
"last 7 days" query on a billion-row table scans 100MB. Done wrong,
every query scans all N partitions and pays the per-partition overhead
on top.

Three flavors, one rule each:

| Strategy | When                                       | Hot-query shape         |
|----------|--------------------------------------------|-------------------------|
| `RANGE`  | Time-series, monotonic key, sliding window | `WHERE ts >= ?`         |
| `LIST`   | Small fixed enum (region, tenant)          | `WHERE region = ?`      |
| `HASH`   | High cardinality, no natural range/list    | `WHERE user_id = ?`     |

The win isn't query speed alone — it's that **dropping old data
becomes a metadata operation** (`DETACH PARTITION` + `DROP TABLE`)
instead of a multi-hour `DELETE` that bloats the table and floods WAL.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 22-db-table-partitioning -am spring-boot:run
```

App boots on **:8222**. Schema `m22_partition`. Three independent
partitioned tables: `events_range`, `events_list`, `events_hash`.

## Run order

```bash
# --- RANGE (the most common in practice: time-series) ---
curl -X POST 'localhost:8222/partition/range/seed?rowsPerMonth=100000' | jq
curl 'localhost:8222/partition/range/prune'    | jq    # touches 1 partition
curl 'localhost:8222/partition/range/no-prune' | jq    # touches ALL

# Sliding window: drop partitions older than 6 months, attach next month's.
curl -X POST 'localhost:8222/partition/range/slide?dropOlderThanMonths=6&attachNext=true' | jq

# --- LIST (region) ---
curl -X POST 'localhost:8222/partition/list/seed?rowsPerRegion=50000' | jq
curl 'localhost:8222/partition/list/prune?region=us-east' | jq

# --- HASH (user_id) ---
curl -X POST 'localhost:8222/partition/hash/seed?rows=400000' | jq
curl 'localhost:8222/partition/hash/prune?userId=42' | jq
```

## What each demo proves

### RANGE — `events_range`, partitioned by `created_at` month

The seed builds 13 partitions: 12 monthly + 1 default. Each gets
`rowsPerMonth` rows.

- **`/range/prune`** runs `WHERE created_at >= now() - interval '7 days'`.
  The plan's `Append` lists exactly one child — this month's partition.
  Add years of history and the runtime doesn't change.
- **`/range/no-prune`** runs `WHERE user_id = 42`. No filter on the
  partition key → every partition is scanned. Rule: **if your hot query
  doesn't filter by the partition key, the key is wrong**.

### LIST — `events_list`, partitioned by `region`

One partition per region (`us-east`, `us-west`, `eu-west`, `ap-south`)
plus a default. The DEFAULT partition is a footgun: a new region you
forgot to create silently lands there, your query for it scans the
default, and you don't notice until the dashboard is wrong. Drop the
default in production unless you have a reason for it.

### HASH — `events_hash`, partitioned by `user_id` MODULUS 8

Postgres computes `hashint8(user_id) % 8` and routes the row.
- Equality on `user_id` prunes to one partition. ✓
- `user_id BETWEEN x AND y` does **NOT** prune — hash partitioning is
  for equality lookups only. ✗
- "Drop old data" can't be done cheaply because partitions aren't
  time-aligned. If you want HASH + sliding window, you need
  sub-partitioning.

### Sliding window — `/range/slide`

The reason RANGE partitioning is worth the operational complexity:

```sql
-- before
ALTER TABLE events_range DETACH PARTITION events_range_202401 CONCURRENTLY;
DROP TABLE events_range_202401;
-- vs.
DELETE FROM events WHERE created_at < '2024-02-01';   -- hours, bloat, WAL
```

The endpoint also creates next month's partition ahead of time so writes
on the 1st don't fail. Add a cron job, you're done.

## The PK rule (the #1 gotcha)

```sql
-- ❌ rejected on a partitioned table
PRIMARY KEY (id)

-- ✅ required: PK includes the partition key
PRIMARY KEY (id, created_at)
```

Postgres can't enforce uniqueness across partitions without indexing
the partition key. This means a global `id` is no longer the unique
key your app code thought it was — `(id, created_at)` is. Plan your
foreign keys, app-side lookups, and ORM mappings around that.

## Foreign keys

| Direction                         | Allowed?               |
|-----------------------------------|------------------------|
| FROM partitioned TO non-partitioned | ✓                    |
| FROM non-partitioned TO partitioned | ✓ (PG12+)            |
| BETWEEN two partitioned tables      | ✓ (PG12+, careful)   |

Postgres ≤ 11 was much more restrictive. If your platform is on an old
version, check carefully.

## Partition pruning, in the EXPLAIN

The clue you want to see in `/range/prune` output:

```
Append  (cost=... rows=...)
  ->  Seq Scan on events_range_202506 events_range_1
        Filter: (created_at >= (now() - '7 days'::interval))
```

Just one child node. No "Subplans Removed" line is needed when the
prune happens at plan time. When the value is only known at execute
time (parameterized query, JOIN), pruning happens later and you'll
see:

```
Subplans Removed: 11
```

— meaning 11 of 12 partitions were eliminated at execution. Both are
fine; both are what you want.

## When NOT to partition

- **< 100M rows or < 50GB.** Single-table indexes handle this fine.
  Partitioning adds planning overhead, more files, more pg_class rows,
  and a constraint on your PK shape. Wait until the table actually
  hurts.
- **Your hot queries don't include the partition key.** Pruning
  doesn't happen → every query touches every partition + per-partition
  overhead = slower than the non-partitioned table.
- **You need a globally unique non-partition-key column.** You can't
  have it. The closest you get is a unique index per partition + an
  app-side check, which is racy.
- **You need cross-partition foreign keys IN AND OUT, on PG ≤ 11.**
  Upgrade or denormalize.

## Sliding window — the maintenance script

```sql
-- run at the start of every month:
BEGIN;
  -- 1. Build next month's partition off-band.
  CREATE TABLE events_range_202507 (LIKE events_range INCLUDING ALL);
  -- 2. Attach it (fast — catalog only, no data move).
  ALTER TABLE events_range ATTACH PARTITION events_range_202507
    FOR VALUES FROM ('2025-07-01') TO ('2025-08-01');
  -- 3. Drop the oldest partition past your retention SLO.
  ALTER TABLE events_range DETACH PARTITION events_range_202401 CONCURRENTLY;
  DROP TABLE events_range_202401;
COMMIT;
```

In Spring/Java, run this via a `@Scheduled` job. In ops, run it via a
DB-side `pg_cron` extension. Either way, the partition count stays
constant — the storage releases what falls off the back.

## Choosing a key — decision tree

```
Is the hot query "newest N" or "between two timestamps"?
   yes → RANGE on the timestamp column.
   no  ↓
Is the partition column an enum of <100 values?
   yes → LIST on that column.
   no  ↓
Are queries "give me everything for THIS id"?
   yes → HASH on that id, MODULUS = roughly your CPU count.
   no  → you don't need partitioning yet. Index harder.
```

## Production checklist

| Symptom                                          | Likely cause                                | Fix                                                          |
|--------------------------------------------------|---------------------------------------------|--------------------------------------------------------------|
| All queries scan every partition                 | Partition key not in WHERE clause           | Pick a different key, or add it to the hot query.            |
| Insert fails: "no partition found for row"       | Missing partition for the value             | Create the partition; or add a DEFAULT (with awareness).     |
| `ALTER TABLE ... PRIMARY KEY` rejected           | PK doesn't include partition key            | Add the partition key to the PK.                             |
| Rows silently appear in `_default` partition     | DEFAULT partition exists                    | Drop DEFAULT once steady-state, or alert on its row count.   |
| Sliding-window job slow                          | Using DETACH (not CONCURRENTLY) under load  | `DETACH PARTITION ... CONCURRENTLY` (PG14+).                 |
| Hash partitioning + range query → no pruning     | Hash only prunes on equality                | Sub-partition by hash inside range; or pick a different key. |
| Lots of small partitions, plan time slow         | Too many partitions (>1000)                 | Coarser granularity (quarterly not daily); or partition tree.|

## Files

```
src/main/java/com/claude/dbpoc/m22/
├── Application.java
├── service/
│   └── PartitionService.java     # RANGE / LIST / HASH + sliding window
└── web/
    └── PartitionController.java  # /partition/{range,list,hash}/*
src/main/resources/
├── application.yml               # port 8222, m22_partition schema
└── schema.sql                    # just creates the schema
```

## Related modules

- **[m02 - query-plan](../02-db-query-plan/)** — how to read the
  EXPLAIN output that proves pruning happened.
- **[m14 - long-transaction](../14-db-long-transaction/)** — why a
  multi-hour `DELETE` on a non-partitioned table is so bad.
- **[m19 - online-ddl](../19-db-online-ddl/)** — the lock-aware DDL
  patterns the sliding-window job depends on.
- **[m21 - read-replica](../21-db-read-replica/)** — replication of
  partitioned tables works, but each partition is its own relation
  → more replication slots used, more visible in `pg_stat_replication`.
