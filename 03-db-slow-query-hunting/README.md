# 03 — Slow Query Hunting

The on-call SRE's daily workflow for finding the worst queries in a production
database, on both **Postgres** (`pg_stat_statements`) and **Oracle** (`V$SQL`,
`V$SQLAREA`, AWR).

This module:

1. Seeds three tables designed to be slow (~6M rows total, with deliberate
   missing indexes).
2. Drives a controlled mixed workload of ~20 distinct queries — a few of which
   are intentionally bad.
3. Exposes the same top-N queries an SRE would type into `psql` or `sqlplus`
   at 3am, plus suggestions for the obvious wins (missing FK indexes).

The Java glue is convenient, but the **most reusable artefact is the SQL** in
[`scripts/topn-postgres.sql`](scripts/topn-postgres.sql) and
[`scripts/topn-oracle.sql`](scripts/topn-oracle.sql).

---

## The workflow

```text
   reset stats   →   run workload   →   read top-N   →   spot pattern   →   propose fix
```

```bash
# Start the module
mvn -pl 03-db-slow-query-hunting spring-boot:run

# 1) Seed the bait (one-time, ~60s)
curl -X POST http://localhost:8203/seed

# 2) Reset pg_stat_statements so we measure only OUR workload
curl -X POST http://localhost:8203/reset

# 3) Run mixed traffic for 60s
curl -X POST 'http://localhost:8203/workload/start?seconds=60'

# 4) Look at the worst offenders by total time
curl 'http://localhost:8203/top?n=10&order=total_time' | jq

# 5) And by mean time
curl 'http://localhost:8203/top?n=10&order=mean_time' | jq

# 6) Ask for the easy wins
curl http://localhost:8203/suggest/missing-fk-indexes | jq
```

Same thing on Oracle once you flip `oracle.enabled=true` and restart:

```bash
curl 'http://localhost:8203/top/oracle?n=10' | jq
curl  http://localhost:8203/suggest/oracle/missing-fk-indexes | jq
```

---

## The four bad queries (and how to spot them)

Each smell below is a query the workload generator runs on purpose. The
"symptom" column tells you what jumps out in `pg_stat_statements`.

### #1 — Missing FK index

```sql
SELECT * FROM transactions WHERE account_id = ?
```

- **Symptom in `pg_stat_statements`**: enormous `mean_exec_time_ms`, huge
  `shared_blks_read`, modest `rows` (a small handful of matching transactions).
- **Why**: `transactions(account_id)` has no index. Every call sequentially
  scans 5M rows.
- **Fix**: `CREATE INDEX CONCURRENTLY ON transactions (account_id);`
- **Find it automatically**: `/suggest/missing-fk-indexes` (or the query in
  `topn-postgres.sql` §5).

### #2 — Missing compound index

```sql
SELECT * FROM audit_log WHERE entity_type = ? AND entity_id = ?
```

- **Symptom**: high `mean_exec_time_ms`, high `shared_blks_read`. Even if
  you added an index on `entity_type` alone, it wouldn't help much because
  selectivity is poor (4 distinct types).
- **Why**: no index on the combined predicate.
- **Fix**: `CREATE INDEX CONCURRENTLY ON audit_log (entity_type, entity_id);`
- **Note**: order matters. `(entity_type, entity_id)` also serves
  `WHERE entity_type = ?` queries; `(entity_id, entity_type)` does not.

### #3 — Function on indexed column (non-sargable)

```sql
SELECT * FROM transactions WHERE EXTRACT(MONTH FROM created_at) = 3
```

- **Symptom**: high mean time, full table read. Even if `created_at` is
  indexed, the planner can't use the B-tree because the predicate is
  `EXTRACT(MONTH FROM x) = 3`, not `x BETWEEN ... AND ...`.
- **Fix (option A — rewrite)**: rewrite as a range predicate so the
  existing date index applies:
  ```sql
  WHERE created_at >= '2024-03-01' AND created_at < '2024-04-01'
  ```
- **Fix (option B — expression index)**: build the index Postgres can use:
  ```sql
  CREATE INDEX ON transactions ((EXTRACT(MONTH FROM created_at)));
  ```
  Generally worse: the rewrite is more flexible and doesn't bloat the table.

### #4 — Implicit type cast disables the index

```sql
SELECT * FROM transactions WHERE account_id::text = ?
```

- **Symptom**: identical to #1. The `::text` cast prevents B-tree usage on
  `account_id` even after you add the FK index.
- **Why**: the predicate becomes a function on the column. The planner
  can't probe the index.
- **Fix**: cast the *parameter*, not the column:
  ```sql
  WHERE account_id = ?::bigint
  ```
  Better: fix the call site to pass the right type in the first place.

---

## Postgres setup requirements

`pg_stat_statements` is an extension that has to be loaded at server start
and enabled per database. The docker-compose in this repo already does both:

```yaml
# docker-compose.yml
postgres:
  command: >
    postgres
    -c shared_preload_libraries=pg_stat_statements,auto_explain
    -c pg_stat_statements.track=all       # capture nested SQL too
    -c pg_stat_statements.max=10000
```

```sql
-- docker/postgres-init.sql
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
```

If you're on a server you don't own, check it's there:

```sql
SHOW shared_preload_libraries;          -- must include pg_stat_statements
SELECT * FROM pg_extension WHERE extname = 'pg_stat_statements';
```

`pg_stat_statements.track = all` is important: with the default `top`, queries
issued from inside PL/pgSQL functions don't get counted. On a system that
puts most of its logic in stored procedures, you'll see almost nothing.

---

## Oracle equivalents

| Postgres                | Oracle                                              |
|-------------------------|-----------------------------------------------------|
| `pg_stat_statements`    | `V$SQL` (per cursor), `V$SQLAREA` (per `sql_id`)    |
| `pg_stat_statements_reset()` | (no exact equivalent — flush shared pool: `ALTER SYSTEM FLUSH SHARED_POOL`; rarely used in prod) |
| `total_exec_time`       | `elapsed_time` (microseconds)                        |
| `shared_blks_read`      | `disk_reads` (physical I/O)                          |
| `shared_blks_hit`       | `buffer_gets` (logical I/O — yes, opposite default) |
| n/a                     | `V$SQLSTATS` — like `V$SQLAREA` but more durable    |
| n/a                     | `DBA_HIST_SQLSTAT` — AWR-backed historical view     |

**AWR retention defaults to 8 days.** This is the single most important
operational footnote: the `V$SQL`-family views only know about cursors
*currently in the shared pool*. Once Oracle evicts the cursor (memory
pressure, restart, recompile), it's gone from `V$SQL`. AWR snapshots
preserve a copy in `DBA_HIST_SQLSTAT`, but only for 8 days by default. If
your post-mortem is "what made the database slow three Tuesdays ago", you
need to have raised AWR retention *in advance*:

```sql
EXEC DBMS_WORKLOAD_REPOSITORY.MODIFY_SNAPSHOT_SETTINGS(retention => 60*24*60);
-- 60 days, in minutes
```

---

## Endpoints (this module)

| Verb  | Path                                       | Purpose                                  |
|-------|--------------------------------------------|------------------------------------------|
| POST  | `/seed`                                    | (re)build the bait tables                |
| POST  | `/workload/start?seconds=N`                | run mixed workload for N seconds (8 threads) |
| POST  | `/reset`                                   | `pg_stat_statements_reset()`             |
| GET   | `/top?n=10&order=total_time\|mean_time\|calls` | top-N from `pg_stat_statements`       |
| GET   | `/top/oracle?n=10`                         | top-N from `V$SQL` (requires `oracle.enabled=true`) |
| GET   | `/suggest/missing-fk-indexes`              | Postgres unindexed-FK report + DDL       |
| GET   | `/suggest/oracle/missing-fk-indexes`       | Oracle equivalent                        |

Port: **8203**.

---

## Limits / what this doesn't cover

- **Locks** — `pg_stat_statements` only tells you about CPU/IO, not waits on
  locks. For lock investigation, use `pg_locks` + `pg_stat_activity`.
- **Plan changes** — a query that suddenly got slow because the planner
  switched plans won't be visible here. You'd reach for `auto_explain` (the
  Postgres setting is on in our compose) or `DBMS_XPLAN.DISPLAY_CURSOR` on
  Oracle.
- **Connection-side issues** — `pg_stat_statements` doesn't see network/RTT
  cost. If `EXPLAIN ANALYZE` says 5ms but the app sees 200ms, the problem
  isn't in this view.
