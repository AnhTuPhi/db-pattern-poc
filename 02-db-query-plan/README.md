# 02 — DB Query Plan

> **The highest-leverage debugging skill in databases isn't writing SQL — it's reading the plan that comes back.**

A plan tells you *what the database is actually going to do*, not what you hoped it would. When a query is slow, the plan tells you why. When you change an index, the plan tells you whether it helped. When a query that ran in 5 ms yesterday runs in 5 seconds today, the plan — and the difference between the old plan and the new one — is the entire story.

This module teaches plan-reading by running **the same predicate against the same data** and showing how the *shape* of the plan changes with selectivity:

| Predicate | Selectivity | Plan shape |
|---|---|---|
| `WHERE status = 'PAID'` | ~70% of rows | **Seq Scan** |
| `WHERE customer_id = 42` | ~0.15% of rows | **Index Scan** |
| `WHERE customer_id IN (1..10)` | ~1.5% of rows | **Bitmap Heap Scan** |
| `SELECT id, customer_id WHERE customer_id = 42` | ~0.15%, covered | **Index Only Scan** |

Same table. Same indexes. Different plans. The planner is doing math.

## Setup

```bash
# Just Postgres (recommended starter)
./scripts/setup.sh postgres

# Postgres + Oracle (lets you compare side-by-side)
./scripts/setup.sh core

# Run module 02 (Postgres only)
mvn -pl 02-db-query-plan spring-boot:run

# Run with Oracle enabled
mvn -pl 02-db-query-plan spring-boot:run -Dspring-boot.run.arguments=--oracle.enabled=true
```

The app listens on **:8202**.

## Workflow

### 1. Seed 1M rows

```bash
curl -X POST 'http://localhost:8202/seed?rows=1000000&db=pg'
```

Returns:
```json
{
  "db": "pg",
  "rowsInserted": 1000000,
  "distinctCustomers": 700,
  "statusDistribution": "PAID~70%, PENDING~20%, SHIPPED~7%, CANCELLED~3%",
  "elapsedMs": 6432
}
```

The seeder also runs `ANALYZE orders` — without fresh stats every demo below would be a lie.

### 2. Walk through each plan endpoint

#### Seq Scan
```bash
curl http://localhost:8202/plans/seq-scan | jq .
```

Expected raw output (yours will vary in numbers):
```
Seq Scan on orders  (cost=0.00..21370.00 rows=698432 width=37) (actual time=0.012..78.214 rows=699127 loops=1)
  Filter: ((status)::text = 'PAID'::text)
  Rows Removed by Filter: 300873
  Buffers: shared hit=8870
Planning Time: 0.097 ms
Execution Time: 91.331 ms
```

**Line-by-line:**
- `Seq Scan on orders` — the operator. Reads every page of the heap in physical order.
- `cost=0.00..21370.00` — planner's cost units (NOT milliseconds). First number = startup cost (cost to produce the first row). Second = total cost (cost to produce all rows). You compare these between plans, not against wall-clock time.
- `rows=698432` — planner's **estimate** of matching rows.
- `width=37` — average row size in bytes.
- `actual time=0.012..78.214` — wall-clock milliseconds from query start. First = first row; second = last row.
- `rows=699127 loops=1` — what actually happened. The whole node ran once and emitted 699,127 rows. Compare to planned `rows=698432` — within 0.1%, so the stats are good.
- `Filter:` — the predicate applied AFTER reading.
- `Rows Removed by Filter: 300873` — the cost of having no useful index for `status`. Postgres had to read these rows just to throw them away.
- `Buffers: shared hit=8870` — 8870 8KB pages served from the buffer cache. Multiply by 8KB to get bytes read.

#### Index Scan
```bash
curl 'http://localhost:8202/plans/index-scan?customerId=42' | jq .
```

Expected:
```
Index Scan using idx_orders_customer on orders  (cost=0.42..58.31 rows=1429 width=37) (actual time=0.024..0.412 rows=1421 loops=1)
  Index Cond: (customer_id = 42)
  Buffers: shared hit=15
```

Note `shared hit=15` (vs ~8870 for the Seq Scan). Same data, ~600x less I/O.

#### Bitmap Heap Scan
```bash
curl 'http://localhost:8202/plans/bitmap-scan?ids=1,2,3,4,5,6,7,8,9,10' | jq .
```

Expected:
```
Bitmap Heap Scan on orders  (cost=82.10..6789.45 rows=14290 width=37) (actual time=2.1..18.4 rows=14283 loops=1)
  Recheck Cond: (customer_id = ANY ('{1,2,...,10}'::bigint[]))
  Heap Blocks: exact=4500
  Buffers: shared hit=4515
  ->  Bitmap Index Scan on idx_orders_customer  (cost=0.00..78.5 rows=14290 width=0)
        Index Cond: (customer_id = ANY (...))
        Buffers: shared hit=15
```

Two-step plan:
1. **Bitmap Index Scan** walks the index once and builds a bitmap of which heap pages contain matches (sorted by page number).
2. **Bitmap Heap Scan** visits each page **once**, in physical disk order, instead of the random-I/O zig-zag of a plain Index Scan.

The crossover happens when the number of matching rows is big enough that random I/O on the heap dominates — typically 5–20 keys for this table size. Try `?ids=1,2` (back to Index Scan) and `?ids=1,2,...,200` (back to Seq Scan) to see both edges.

#### Index Only Scan
```bash
curl 'http://localhost:8202/plans/index-only-scan?customerId=42' | jq .
```

Expected:
```
Index Only Scan using idx_orders_customer on orders  (cost=0.42..32.10 rows=1429 width=16) (actual time=0.018..0.142 rows=1421 loops=1)
  Index Cond: (customer_id = 42)
  Heap Fetches: 0
  Buffers: shared hit=8
```

The headline is **`Heap Fetches: 0`** — Postgres served every row directly from the index without ever touching the heap. This requires:
1. All selected columns live in the index (here, `id` is the heap pointer + `customer_id` is the indexed column).
2. The visibility map says the pages are all-visible (set by `VACUUM`).

If `Heap Fetches: > 0`, run `VACUUM orders;` and try again.

### 3. Force a plan to PROVE the planner was right

```bash
curl -X POST http://localhost:8202/compare/disable-seqscan | jq .
# Look at totalCost — it'll be HIGHER than the Seq Scan baseline.
curl -X POST http://localhost:8202/compare/enable-seqscan
```

`SET enable_seqscan = off` doesn't actually forbid Seq Scan — it slaps a huge cost penalty on it so the planner picks something else. If that something-else has a higher cost, the planner was right. **This is a diagnostic, not a fix.** Never ship `SET enable_seqscan = off` to production.

If the forced plan is *cheaper* than the planner's choice, your statistics are wrong. Fix the stats (`ANALYZE`, increase `default_statistics_target`, add extended stats), don't fight the planner.

### 4. Raw SQL workflow (the DBA's path)

```bash
./scripts/pg-plan.sh        # same queries, saved to scripts/out/
./scripts/oracle-plan.sh    # Oracle equivalent (needs core profile)
```

## Glossary — what every EXPLAIN line means

| Token | What it means |
|---|---|
| `cost=X..Y` | Planner's abstract cost units. X = startup, Y = total. Compare BETWEEN plans, not against ms. |
| `rows=N` | Planner's **estimate** of how many rows this node will emit. |
| `width=N` | Average row size in bytes (used in memory estimates). |
| `actual time=A..B` | Wall-clock ms. A = time to first row, B = time to last row. |
| `rows=N loops=L` | Actual rows emitted per loop × loop count. For nested loop inner side, multiply. |
| `Buffers: shared hit=N` | 8KB pages served from the buffer cache (free). |
| `Buffers: shared read=N` | 8KB pages read from disk (expensive). |
| `Buffers: shared dirtied=N` | Pages this query modified. |
| `Heap Fetches: N` | Index Only Scan: how many times we had to fall back to the heap. 0 = perfect. |
| `Rows Removed by Filter: N` | Rows read but discarded. High = missing index or wrong predicate. |
| `Planning Time` | Time the planner spent. Usually negligible unless you have hundreds of joins. |
| `Execution Time` | Wall-clock total. Compare against `actual time` of the top node to see overhead. |

## The cardinality lesson

The **#1 root cause** of bad plans is the planner estimating the wrong number of rows.

```
->  Index Scan on orders  (cost=0.42..58.31 rows=2 width=37) (actual time=0.024..412.31 rows=421988 loops=1)
                                                  ^^^^^^                                ^^^^^^^^^^^^
                                                  planned                                actual — 200000x off
```

When `planned` and `actual` diverge by >10x, every plan choice downstream is suspect. The fix is in *statistics*, not the SQL:
- Run `ANALYZE` (or `DBMS_STATS.GATHER_TABLE_STATS` on Oracle).
- Bump `default_statistics_target` for the column (Postgres) or use histograms (Oracle).
- For correlated columns, create extended stats: `CREATE STATISTICS ... ON (col_a, col_b) FROM table`.

Module **04 — db-cardinality-estimation** is dedicated to this — every plan you'll read for the rest of your career assumes the planner had good cardinality estimates.

## Postgres ↔ Oracle equivalents

| Postgres | Oracle |
|---|---|
| `EXPLAIN ANALYZE <sql>` | `EXPLAIN PLAN FOR <sql>` then `SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY())` |
| `EXPLAIN (ANALYZE)` with actuals | `SELECT /*+ GATHER_PLAN_STATISTICS */` then `DBMS_XPLAN.DISPLAY_CURSOR(format=>'ALLSTATS LAST')` |
| Seq Scan | TABLE ACCESS FULL |
| Index Scan | INDEX RANGE SCAN + TABLE ACCESS BY INDEX ROWID |
| Index Only Scan | INDEX FAST FULL SCAN (when covering) |
| Bitmap Heap Scan | Bitmap conversion / INLIST ITERATOR |
| Bitmap Index Scan | BITMAP INDEX SINGLE VALUE |
| Hash Join | HASH JOIN |
| Nested Loop | NESTED LOOPS |
| Merge Join | MERGE JOIN |
| `SET enable_seqscan = off` | `/*+ FULL(t) */` or `/*+ INDEX(t idx) */` hint |
| `ANALYZE <table>` | `BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER,'TABLE'); END;` |
| `pg_stat_user_tables.last_analyze` | `USER_TAB_STATISTICS.LAST_ANALYZED` |
| `auto_explain` extension | `DBMS_SQLTUNE` advisor + AWR reports |

## Endpoint cheat-sheet

| Endpoint | What it shows |
|---|---|
| `POST /seed?rows=N&db=pg\|oracle` | (Re)create schema, insert N rows, run ANALYZE/DBMS_STATS |
| `GET  /plans/seq-scan` | High-selectivity predicate forces Seq Scan |
| `GET  /plans/index-scan?customerId=N` | Low-selectivity → Index Scan |
| `GET  /plans/bitmap-scan?ids=1,2,...` | Medium selectivity → Bitmap Heap Scan |
| `GET  /plans/index-only-scan?customerId=N` | Covered query → Index Only Scan (watch `Heap Fetches`) |
| `POST /compare/disable-seqscan` | Force a non-Seq plan, compare cost |
| `POST /compare/enable-seqscan` | Restore default |
| `POST /compare/oracle/hint?customerId=N&hint=FULL\|INDEX\|NONE` | Oracle hint override (Oracle profile only) |
