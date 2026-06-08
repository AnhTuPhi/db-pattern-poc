# 04 — DB Cardinality Estimation

> **Bad plans are usually bad estimates.**
>
> When a query suddenly turns slow in production and nothing about the SQL changed,
> the answer is almost never "the planner got dumber". It's that the planner's
> picture of your data drifted out of sync with reality, and a plan that used to
> be optimal now isn't. This module makes that drift visible and walks through
> the three most common causes.

## The thesis

Postgres' planner picks a plan by estimating how many rows each node will
produce, then costing the alternatives. The numbers it uses come from `pg_stats`
(per-column) and `pg_statistic_ext` (multi-column). When those numbers are
wrong, the costs are wrong, and the chosen plan is wrong.

Three failure modes account for the overwhelming majority of production
"why is this query suddenly slow?" tickets:

1. **Stale statistics** — pg_stats hasn't caught up with a recent bulk load.
2. **Correlated columns** — the planner assumes independence and multiplies
   selectivities, missing the true count by orders of magnitude.
3. **Skew** — one column value dominates, and either the MCV list captures it
   (good plans) or it doesn't (random plans).

Each demo below runs `EXPLAIN (ANALYZE, FORMAT JSON)` and surfaces
`{estimatedRows, actualRows, ratioOff, planChosen}` in the response so the
divergence is the first thing you see.

## Run it

```bash
# Bring up Postgres if not already running:
../scripts/setup.sh postgres

# Start the module:
mvn -pl 04-db-cardinality-estimation spring-boot:run
```

Endpoints live under `http://localhost:8204`.

## Demo 1: Stale stats

```bash
# Load 1M rows. NOTE: this deliberately does NOT run ANALYZE.
curl -X POST "http://localhost:8204/seed/initial?rows=1000000"

# Same query, before and after ANALYZE:
curl "http://localhost:8204/demo/stale-stats"
#   → estimatedRows: e.g. 200000   (Postgres' default guess from n_distinct=-1)
#   → actualRows:    900000        (90% of the table is 'US')
#   → ratioOff:      0.22          (planner thinks the result is 5× smaller than it is)
#   → planChosen:    Index Scan / Bitmap Heap Scan  ← WRONG for 90% of the table

curl -X POST "http://localhost:8204/seed/analyze"

curl "http://localhost:8204/demo/stale-stats"
#   → estimatedRows: ~900000
#   → actualRows:    900000
#   → ratioOff:      ~1.0
#   → planChosen:    Seq Scan      ← correct: when you need 90% of the table, scan it
```

**What this proves:** `ANALYZE` is the cheapest, safest fix in the toolbox.
Autovacuum runs it eventually, but "eventually" can mean "after your incident".

## Demo 2: Correlated columns

```bash
# Make sure stats are fresh first:
curl -X POST "http://localhost:8204/seed/analyze"

curl "http://localhost:8204/demo/correlation?country=US&region=CALIFORNIA"
#   → estimatedRows: e.g. 36000    (planner: 0.90 × 0.20 × 1M = 180k... but
#                                   per-column histogram interactions push it
#                                   down further — the point is, it's *off*)
#   → actualRows:    ~180000       (true: every CALIFORNIA row IS a US row,
#                                   the second predicate is redundant)
#   → ratioOff:      ~0.2          (planner under-estimates by 5×)
#   → extendedStatsPresent: false
```

The planner assumed `country_code` and `region` are independent. They aren't —
'CALIFORNIA' implies 'US'. Per-column stats can't represent that relationship.

```bash
curl -X POST "http://localhost:8204/demo/correlation/fix"
# Runs:  CREATE STATISTICS orders_country_region (dependencies, ndistinct, mcv)
#                          ON country_code, region FROM orders;
#        ANALYZE orders;

curl "http://localhost:8204/demo/correlation?country=US&region=CALIFORNIA"
#   → estimatedRows: ~180000
#   → actualRows:    ~180000
#   → ratioOff:      ~1.0
#   → extendedStatsPresent: true
```

**What this proves:** for correlated predicates, `ANALYZE` alone won't save
you — per-column stats *cannot* express the relationship. You need extended
statistics. This is the only general fix.

## Demo 3: Skew

```bash
# A common value (90% of the table) — Seq Scan is correct:
curl "http://localhost:8204/demo/skew?country=US"
#   → estimatedRows: ~900000
#   → planChosen:    Seq Scan   ← good

# A rare value (~10 rows in 1M) — Index Scan is correct:
curl "http://localhost:8204/demo/skew?country=AQ"
#   → estimatedRows: ~10
#   → planChosen:    Index Scan using idx_orders_country   ← good

# If the rare value isn't in the MCV list (e.g. stats target=100 and 'AQ' got
# pushed out by other values), Postgres falls back on the histogram remainder
# and may guess "a few thousand" — flipping the plan to Seq Scan.
curl -X POST "http://localhost:8204/stats/target?column=country_code&target=1000"
# Now MCV is deep enough to include 'AQ'. Re-run /demo/skew?country=AQ.
```

**What this proves:** the planner is right *when its picture is detailed
enough*. For long-tailed columns, the default `default_statistics_target=100`
can be too shallow.

## Inspecting the planner's view

```bash
# What pg_stats actually contains right now:
curl "http://localhost:8204/stats/columns"

# Show the diagnostic SQL you'd reach for in production:
psql -h localhost -U appuser -d appdb -f scripts/diagnose-bad-plans.sql
```

## Fixes, ranked by sledgehammer level

| Level | Fix | When to use |
| --- | --- | --- |
| 1 | `ANALYZE table` | **Always try first.** Free, safe, idempotent. Fixes stale-stats problems. |
| 2 | `ALTER TABLE ... ALTER COLUMN x SET STATISTICS N` then re-ANALYZE | When a column's MCV list / histogram is too shallow for its distribution. Typical bump: 100 → 1000. |
| 3 | `CREATE STATISTICS ... (dependencies, ndistinct, mcv) ON a, b FROM t` then re-ANALYZE | **The only fix for correlated columns.** Teaches the planner the relationship per-column stats can't express. |
| 4 | Query rewrite / `pg_hint_plan` / schema redesign | Last resort. If the above three can't get the estimate within 10× of reality, the data shape may need to change. |

Use the cheapest fix that works. Most "the planner is broken" tickets are
solved at level 1.

## Oracle equivalents

| Postgres | Oracle |
| --- | --- |
| `ANALYZE table` | `DBMS_STATS.GATHER_TABLE_STATS(user, 'TABLE_NAME')` |
| Per-column histogram resolution (`SET STATISTICS`) | `METHOD_OPT => 'FOR COLUMNS SIZE 254 col_name'` in `GATHER_TABLE_STATS` |
| `CREATE STATISTICS (dependencies, ndistinct, mcv) ON a, b` | `DBMS_STATS.CREATE_EXTENDED_STATS(user, 'TABLE_NAME', '(a, b)')` then re-gather |
| `pg_stats` / `pg_statistic_ext` | `USER_TAB_COL_STATISTICS`, `USER_TAB_HISTOGRAMS`, `USER_STAT_EXTENSIONS` |
| Autovacuum analyze | Automatic stats gathering job (DBA_AUTOTASK_TASK) |
| System cost calibration | `DBMS_STATS.GATHER_SYSTEM_STATS` |

The lesson is identical on both engines: **the planner's estimates are the
foundation of every plan choice, and the foundation needs to be kept in sync
with reality**. The mechanics differ; the mindset doesn't.

## Files

```
src/main/java/com/claude/dbpoc/m04/
    Application.java          Spring Boot entry point
    SeedController.java       POST /seed/initial, /seed/bulk-load, /seed/analyze
    DemoController.java       GET  /demo/stale-stats, /demo/correlation, /demo/skew
                              POST /demo/correlation/fix
    StatsController.java      GET  /stats/columns,  POST /stats/target
    EstimateExtractor.java    EXPLAIN ANALYZE → {estimatedRows, actualRows, ratio}

src/main/resources/
    application.yml           Postgres-only; port 8204; schema m04_cardinality
    schema.sql                orders + three indexes (country, country+region, customer)

scripts/
    diagnose-bad-plans.sql    psql cookbook: pg_stat_user_tables, pg_stats,
                              pg_stat_statements, CREATE STATISTICS patterns
```
