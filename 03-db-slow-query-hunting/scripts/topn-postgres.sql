-- =============================================================================
-- topn-postgres.sql
--
-- The day-one psql snippet for hunting slow queries in a Postgres database.
-- Paste straight into `psql` against your production replica.
--
-- Requirements on the server:
--   shared_preload_libraries = 'pg_stat_statements'   (restart-only setting)
--   CREATE EXTENSION pg_stat_statements;              (once per database)
--   pg_stat_statements.track = all                    (so nested queries count)
--
-- The view exposes ~30 columns per *normalised* query text. "Normalised" means
-- literal parameters are collapsed to `$1`, `$2`, etc., so `WHERE id = 1` and
-- `WHERE id = 2` aggregate into one row. That's the whole point.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 0) Reset the counters for a clean experiment.
--
-- pg_stat_statements is cumulative since the last reset or server restart.
-- For a focused exercise (e.g., "I want to see only what /workload generates"),
-- reset, run the workload, then read /top.
-- -----------------------------------------------------------------------------
SELECT pg_stat_statements_reset();


-- -----------------------------------------------------------------------------
-- 1) Top-10 by TOTAL execution time.
--
-- This is the "what hurts most overall" view. A query that runs 1ms but is
-- called 10M times can outweigh a 5s query called 3 times. Always start here.
-- -----------------------------------------------------------------------------
SELECT
    substr(query, 1, 120)                       AS query,
    calls,
    round(total_exec_time::numeric, 2)          AS total_ms,
    round(mean_exec_time::numeric, 2)           AS mean_ms,
    rows,
    shared_blks_read                            AS blks_read_from_disk,
    shared_blks_hit                             AS blks_from_buffer_cache
FROM   pg_stat_statements
WHERE  query NOT ILIKE '%pg_stat_statements%'
  AND  query NOT ILIKE '%pg_catalog.%'
ORDER  BY total_exec_time DESC
LIMIT  10;


-- -----------------------------------------------------------------------------
-- 2) Top-10 by MEAN execution time.
--
-- The "individually slow" view. Great for spotting that one analytical
-- monster that runs rarely but blocks a thread for 30s when it does.
-- -----------------------------------------------------------------------------
SELECT
    substr(query, 1, 120)                       AS query,
    calls,
    round(mean_exec_time::numeric, 2)           AS mean_ms,
    round(total_exec_time::numeric, 2)          AS total_ms,
    rows
FROM   pg_stat_statements
WHERE  calls > 5                                -- ignore one-off spikes
  AND  query NOT ILIKE '%pg_stat_statements%'
ORDER  BY mean_exec_time DESC
LIMIT  10;


-- -----------------------------------------------------------------------------
-- 3) Top-10 by I/O — queries that hit disk hardest.
--
-- shared_blks_read is reads from disk (cache miss). shared_blks_hit is from
-- the buffer cache. A high read/(hit+read) ratio means you're missing the
-- cache, often because the table is huge and unindexed for the predicate.
-- -----------------------------------------------------------------------------
SELECT
    substr(query, 1, 120)                       AS query,
    calls,
    shared_blks_read                            AS disk_reads,
    shared_blks_hit                             AS cache_hits,
    round(
        100.0 * shared_blks_read
        / NULLIF(shared_blks_read + shared_blks_hit, 0),
        2
    )                                           AS pct_from_disk,
    round(mean_exec_time::numeric, 2)           AS mean_ms
FROM   pg_stat_statements
WHERE  shared_blks_read + shared_blks_hit > 0
ORDER  BY shared_blks_read DESC
LIMIT  10;


-- -----------------------------------------------------------------------------
-- 4) Top-10 by ROWS RETURNED per call — the "shipping too much" smell.
--
-- A query whose `rows / calls` is huge is often a SELECT * with no LIMIT
-- doing in-app filtering — symptom of an ORM that fell back to loading
-- everything. Pair this with auto_explain output for confirmation.
-- -----------------------------------------------------------------------------
SELECT
    substr(query, 1, 120)                       AS query,
    calls,
    rows,
    round(rows::numeric / NULLIF(calls, 0), 1)  AS rows_per_call,
    round(mean_exec_time::numeric, 2)           AS mean_ms
FROM   pg_stat_statements
WHERE  calls > 0
ORDER  BY rows::numeric / NULLIF(calls, 0) DESC
LIMIT  10;


-- -----------------------------------------------------------------------------
-- 5) Find unindexed foreign keys — the lowest-effort, highest-value fix.
--
-- Every FK should normally have a supporting index on the referencing column.
-- Without it, parent UPDATE/DELETE locks the whole child table and the natural
-- `WHERE fk_col = ?` lookup does a Seq Scan.
-- -----------------------------------------------------------------------------
SELECT
    (conrelid::regclass)::text          AS table_name,
    conname                             AS constraint_name,
    a.attname                           AS column_name,
    (confrelid::regclass)::text         AS references_table,
    'CREATE INDEX CONCURRENTLY ON '
        || (conrelid::regclass)::text
        || ' (' || a.attname || ');'    AS suggested_ddl
FROM   pg_constraint c
JOIN   pg_namespace  n ON n.oid = c.connamespace
JOIN   pg_attribute  a ON a.attrelid = c.conrelid
                      AND a.attnum   = c.conkey[1]
WHERE  c.contype = 'f'
  AND  n.nspname NOT IN ('pg_catalog', 'information_schema')
  AND  NOT EXISTS (
      SELECT 1
      FROM   pg_index i
      WHERE  i.indrelid = c.conrelid
        AND  i.indkey[0] = c.conkey[1]
  )
ORDER  BY (conrelid::regclass)::text, conname;


-- -----------------------------------------------------------------------------
-- 6) Find unused indexes — the dead weight on writes.
--
-- pg_stat_user_indexes.idx_scan is the count of index scans since the stats
-- were reset. A high size + zero scans = candidate for DROP INDEX. Don't act
-- on this without confirming the index is unused over a *full business cycle*
-- (e.g., month-end reporting). Old DBA rule: leave it 30 days before dropping.
-- -----------------------------------------------------------------------------
SELECT
    schemaname,
    relname                                                AS table_name,
    indexrelname                                           AS index_name,
    idx_scan                                               AS scans,
    pg_size_pretty(pg_relation_size(indexrelid))           AS index_size
FROM   pg_stat_user_indexes
WHERE  idx_scan = 0
  AND  schemaname NOT IN ('pg_catalog', 'information_schema')
ORDER  BY pg_relation_size(indexrelid) DESC
LIMIT  20;
