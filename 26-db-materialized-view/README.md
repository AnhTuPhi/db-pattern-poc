# 26 - DB Materialized View (precompute, refresh, staleness, alternatives)

> A materialized view is a cached query result.
> Like every cache, the hard part isn't reading it — it's keeping it
> fresh.

## Thesis

A view is a saved query: every read re-runs the underlying joins and
aggregates. A **materialized** view stores the result in a real
table-shaped relation. Reads are O(rows-in-MV), not O(input). The
trade-off: it's a SNAPSHOT — stale until you `REFRESH`.

Three refresh modes in Postgres, each its own kind of pain:

| Mode                          | Locks?         | Speed       | Notes                                           |
|-------------------------------|----------------|-------------|-------------------------------------------------|
| `REFRESH MATERIALIZED VIEW`   | `AccessExclusive` on MV | ⚡ fast | Reads block during refresh.                     |
| `REFRESH ... CONCURRENTLY`    | row-level only  | 🐢 slower  | Reads don't block. Needs UNIQUE index on the MV.|
| `pg_ivm` (extension)          | incremental     | varies     | Maintains the MV per-write. Heavy install.      |

This module benchmarks the analytical query on the base tables vs the
MV, shows both refresh modes, demonstrates the staleness window, and
compares to two alternatives: a **computed column** kept fresh by a
trigger (always-fresh, write-side cost) and **denormalization**
(DIY MV with manual maintenance).

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 26-db-materialized-view -am spring-boot:run
```

App boots on **:8226**. Schema `m26_mv`. A scheduled refresher fires
every 60 seconds (configurable via `mv.refresh-interval-ms`).

## Run order

```bash
# 0. Build the base tables — 100k orders × ~5 items each.
curl -X POST 'localhost:8226/mv/seed?orders=100000' | jq

# 1. Time the analytical query against the base tables.
curl 'localhost:8226/mv/time-expensive' | jq

# 2. Create the MV (with a unique index so we can refresh concurrently).
curl -X POST 'localhost:8226/mv/create' | jq

# 3. Time the SAME query result through the MV.
curl 'localhost:8226/mv/time-mv' | jq
#  → typically 50-200× faster than the base-table query.

# 4. Insert a new sale; show MV is stale until refresh.
curl -X POST 'localhost:8226/mv/mutate?amount=999.99' | jq

# 5. Refresh, blocking.
curl -X POST 'localhost:8226/mv/refresh-blocking' | jq

# 6. Refresh, non-blocking.
curl -X POST 'localhost:8226/mv/refresh-concurrent' | jq

# 7. Alternative: a computed column kept fresh by a trigger.
curl -X POST 'localhost:8226/mv/computed-column' | jq

# 8. Scheduler status.
curl 'localhost:8226/mv/status' | jq
```

## The numbers you'll see

For 100k orders × 5 items × 365 days:

```
time-expensive   ~ 400 - 1500 ms     (3-way join + group by, seq scans)
time-mv          ~     5 -  20 ms    (read ~60 rows, no join)
refresh-blocking ~   150 -  400 ms   (full rebuild)
refresh-concurrent ~ 300 -  900 ms   (rebuild side + diff + apply)
```

The base-table query's plan shows `HashAggregate` over a `Hash Join`
over a `Hash Join` over multiple seq scans — costly. The MV plan is a
single `Seq Scan on monthly_sales`.

## REFRESH CONCURRENTLY — what's it actually doing?

```
1. CREATE a side temp materialized view with the new contents.
2. Diff the temp vs the current MV by UNIQUE-INDEX keys.
3. Apply UPDATE / INSERT / DELETE statements to the current MV
   to make it match.
4. Drop the side.
```

Steps 1-2 are the slow part. The result: readers see the OLD data
until step 3 commits, then the NEW data. No blocking. The cost: roughly
2-3× the runtime of a plain refresh, plus the disk for the side copy
during the build.

**Without a unique index on the MV, `REFRESH CONCURRENTLY` fails**:
```
ERROR:  cannot refresh materialized view "monthly_sales" concurrently
HINT:   Create a unique index with no WHERE clause on one or more columns
        of the materialized view.
```

## Refresh strategies — pick by SLO

| Strategy                | When                                | Mechanism                          |
|-------------------------|-------------------------------------|------------------------------------|
| **Manual** (ops-driven) | Reports run on demand               | `psql -c "refresh materialized view ..."`. |
| **Scheduled**           | Dashboard tolerates N min staleness | `@Scheduled` Spring job; or `pg_cron`. |
| **Trigger-driven full** | Tiny MV; freshness matters          | Trigger on base tables → refresh job. Beware: triggers run inside the write tx. |
| **Trigger-driven incremental** | Real incremental needs         | `pg_ivm` extension; or manual delta tables. |
| **Outbox-pushed**       | Cross-system MV (Elastic etc.)      | m27 territory — outbox → consumer → upsert. |

For "the dashboard data is up to 1 minute stale" — `@Scheduled` +
`REFRESH CONCURRENTLY` covers 90% of cases. The `ScheduledRefresher`
bean in this module shows exactly that pattern, with last-success
metadata so you can monitor it.

## MV vs computed column vs denormalization

```
                           Always fresh?  | Write cost   | Read cost
MV + refresh                  no          | refresh job  | O(mv rows)
Computed column via trigger   yes         | per-write tx | O(1) on row
Denormalized + manual sync    yes         | DIY          | O(1) on row
pg_ivm incremental MV         yes         | per-write tx | O(mv rows)
View (non-materialized)       yes         | none         | O(input)
```

The decision pivot is **freshness SLO vs write-path budget**:

- "Eventually accurate, fast reads": MV with scheduled refresh.
- "Always accurate, single-row read": computed column via trigger.
- "Always accurate, complex aggregate read": pg_ivm or hand-rolled
  incremental tables.
- "Reads are not the bottleneck": don't materialize at all.

The trigger approach (`/mv/computed-column`) installs a row-level
trigger that recomputes `orders.total` on every change to
`order_items`. It works fine for low write rates. For 10k inserts/s
into a hot order it'll contend on the parent row lock; you'd want to
batch the recompute or accept eventual consistency.

## Anti-patterns

- **MV on a hot OLTP table refreshed every 10s.** Refresh CPU + I/O
  starts to dominate. Either lengthen the interval, switch to
  incremental, or restructure (m27 outbox → search index).
- **MV without unique index.** You'll need it the day you want to
  switch to CONCURRENTLY and it'll be too late to add cheaply.
- **MV containing `NOW()` or other volatile functions.** The MV's
  notion of "today" is frozen at refresh time; queries that filter
  `WHERE month = current_month` on the MV are wrong by the next day.
  Refresh frequency must match.
- **Long-running refresh blocking the dashboard.** Use CONCURRENTLY,
  always, for user-facing MVs.
- **Refreshing inside a user transaction.** Don't — the refresh time
  becomes part of the user's API latency.

## Observability

```sql
-- last refresh time, per MV
SELECT schemaname, matviewname,
       hasindexes, ispopulated,
       pg_size_pretty(pg_total_relation_size(format('%I.%I', schemaname, matviewname)::regclass)) as size
FROM pg_matviews;

-- a longer-running refresh in-flight
SELECT pid, state, query, now() - query_start AS running_for
FROM pg_stat_activity
WHERE query ILIKE 'refresh materialized view%';
```

Alert on `now() - last_refresh > 2 × refresh_interval`. That means
the refresher silently failed.

## Production checklist

| Symptom                                            | Likely cause                                    | Fix                                                          |
|----------------------------------------------------|-------------------------------------------------|--------------------------------------------------------------|
| Dashboard shows stale numbers                      | Refresh job not running                         | Health-check the refresher; alert on missing last-success.   |
| Refresh blocks reads                               | Using `REFRESH MATERIALIZED VIEW` (not CONCURRENTLY) | Switch + add unique index.                                  |
| Refresh CONCURRENTLY fails                         | No unique index                                 | Add one. Or fall back to a maintenance window for blocking. |
| Refresh wall-clock balloons                        | Source data grew; full rebuild now slow         | Add WHERE clause to MV; or switch to incremental (pg_ivm). |
| MV bigger than expected                            | TOAST + index overhead                          | `pg_size_pretty(pg_total_relation_size(...))`; sanity-check.|
| Trigger-based column dead-locking on concurrent inserts | Multiple writes per parent row contending | Batch the recompute; or move to MV with scheduled refresh.  |
| The MV "diff" refresh is slow even on small changes | Bad UNIQUE index; or table too wide              | Tighten the unique-index column list; project fewer columns.|

## Files

```
src/main/java/com/claude/dbpoc/m26/
├── Application.java                       # @EnableScheduling
├── service/
│   ├── MaterializedViewService.java       # seed/create/time/refresh/computed-column
│   └── ScheduledRefresher.java            # @Scheduled refresh CONCURRENTLY
└── web/
    └── MaterializedViewController.java    # /mv/{seed,create,time-...,refresh-...,status}
src/main/resources/
├── application.yml                        # port 8226; mv.refresh-interval-ms
└── schema.sql                             # create schema m26_mv
```

## Related modules

- **[m02 - query-plan](../02-db-query-plan/)** — what to look for in
  the EXPLAIN to know "this query is a candidate for an MV".
- **[m22 - table-partitioning](../22-db-table-partitioning/)** — MV
  on a partitioned table; refresh per-partition is a real pattern.
- **[m25 - caching-layers](../25-db-caching-layers/)** — Redis is a
  cache outside the DB; MV is a cache inside the DB. Different
  consistency stories.
- **[m27 - cqrs](../27-db-cqrs/)** — when the read model needs to
  leave Postgres entirely, you've graduated from MVs to CQRS.
