-- ============================================================================
-- diagnose-bad-plans.sql
--
-- Canonical psql queries for chasing "the planner has a bad picture of my data"
-- in production. Designed to be copy-pasted at 3am.
--
-- Usage:
--     psql -h localhost -U appuser -d appdb -f diagnose-bad-plans.sql
--
-- Or just open it and run the sections you care about.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. Tables most in need of ANALYZE
--
-- n_mod_since_analyze is the count of inserts/updates/deletes since the last
-- ANALYZE. A large value relative to n_live_tup means pg_stats is stale and
-- the planner is making decisions on a phantom picture of the table.
--
-- Rule of thumb worth investigating: n_mod_since_analyze > 10% of n_live_tup.
-- ---------------------------------------------------------------------------
SELECT
    schemaname,
    relname                                              AS table_name,
    n_live_tup,
    n_mod_since_analyze,
    CASE WHEN n_live_tup > 0
         THEN round(100.0 * n_mod_since_analyze / n_live_tup, 2)
         ELSE NULL
    END                                                  AS pct_modified,
    last_analyze,
    last_autoanalyze
FROM pg_stat_user_tables
WHERE n_mod_since_analyze > 0
ORDER BY n_mod_since_analyze DESC
LIMIT 20;


-- ---------------------------------------------------------------------------
-- 2. Find query plans where the planner is most wrong
--
-- pg_stat_statements doesn't store estimated-vs-actual ratios directly, so the
-- best signal is execution time wildly higher than mean — those are the queries
-- where re-running EXPLAIN ANALYZE and comparing Plan Rows vs Actual Rows is
-- most likely to pay off.
--
-- Requires CREATE EXTENSION pg_stat_statements; in postgresql.conf:
--     shared_preload_libraries = 'pg_stat_statements'
-- ---------------------------------------------------------------------------
SELECT
    substring(query, 1, 80)                              AS query_snippet,
    calls,
    round(mean_exec_time::numeric, 2)                    AS mean_ms,
    round(max_exec_time::numeric, 2)                     AS max_ms,
    round(stddev_exec_time::numeric, 2)                  AS stddev_ms,
    round((max_exec_time / NULLIF(mean_exec_time, 0))::numeric, 1) AS max_to_mean_ratio,
    rows
FROM pg_stat_statements
WHERE calls > 10
ORDER BY (max_exec_time / NULLIF(mean_exec_time, 0)) DESC NULLS LAST
LIMIT 20;


-- ---------------------------------------------------------------------------
-- 3. What's in pg_stats right now for a given table
--
-- Replace 'orders' with the table you suspect. Look for:
--   - n_distinct = -1 ⇒ default, never analyzed
--   - most_common_vals NULL ⇒ never analyzed
--   - n_distinct < 0 ⇒ ratio of distinct values; -1.0 means "all distinct"
--   - correlation close to 1 or -1 ⇒ physical order matches logical order,
--     index scans on this column will be cheap
-- ---------------------------------------------------------------------------
SELECT
    attname                                              AS column_name,
    n_distinct,
    array_length(most_common_vals, 1)                    AS mcv_count,
    most_common_vals,
    most_common_freqs,
    correlation
FROM pg_stats
WHERE schemaname = 'm04_cardinality'
  AND tablename  = 'orders'
ORDER BY attname;


-- ---------------------------------------------------------------------------
-- 4. CREATE STATISTICS patterns — fixing correlated columns
--
-- The single most underused feature for production planner problems. If you've
-- ever seen "the planner thinks the join produces 50 rows but it actually
-- produces 500,000", this is usually the fix.
--
-- Three kinds (all can be combined in one statement):
--   dependencies : functional dependencies — fixes selectivity multiplication
--                  for AND-of-equalities like WHERE country='US' AND region='CA'.
--   ndistinct    : multi-column distinct count — fixes GROUP BY estimates on
--                  (country, region) etc.
--   mcv          : multivariate most-common-values list (PG 12+) — fixes
--                  estimates for specific common combinations.
-- ---------------------------------------------------------------------------

-- Combined (recommended starting point):
CREATE STATISTICS IF NOT EXISTS orders_country_region
    (dependencies, ndistinct, mcv)
    ON country_code, region
    FROM m04_cardinality.orders;

-- Single-purpose variants if you want narrower stats:
-- CREATE STATISTICS orders_country_region_dep   (dependencies) ON country_code, region FROM orders;
-- CREATE STATISTICS orders_country_region_nd    (ndistinct)    ON country_code, region FROM orders;
-- CREATE STATISTICS orders_country_region_mcv   (mcv)          ON country_code, region FROM orders;

-- Stats are only populated after ANALYZE:
ANALYZE m04_cardinality.orders;

-- What extended stats objects exist on a given table:
SELECT
    stxname           AS stats_name,
    stxkeys           AS column_attnums,
    stxkind           AS kinds  -- d=dependencies, f=ndistinct, m=mcv
FROM pg_statistic_ext
WHERE stxrelid = 'm04_cardinality.orders'::regclass;


-- ---------------------------------------------------------------------------
-- 5. Raise per-column histogram resolution
--
-- Default is 100 buckets per column. For a high-cardinality column with a long
-- tail (e.g. country_code with 'AQ' at 0.001%), 100 buckets can completely
-- miss the rare values. Bumping to 1000 makes the MCV list deeper.
-- ---------------------------------------------------------------------------
-- ALTER TABLE m04_cardinality.orders ALTER COLUMN country_code SET STATISTICS 1000;
-- ANALYZE m04_cardinality.orders (country_code);
-- Then re-check pg_stats — array_length(most_common_vals, 1) should be larger.
