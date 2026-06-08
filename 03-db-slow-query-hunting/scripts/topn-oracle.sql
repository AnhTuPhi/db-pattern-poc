-- =============================================================================
-- topn-oracle.sql
--
-- The day-one sqlplus snippet for hunting slow queries on Oracle.
-- Paste straight into `sqlplus` (or SQL Developer) against your prod (or a
-- standby — better!) database.
--
-- The Oracle equivalents to pg_stat_statements are:
--   V$SQL        — one row per cursor (child); high resolution.
--   V$SQLAREA    — aggregated by sql_id; the "normal" top-N source.
--   V$SQLSTATS   — like V$SQLAREA but survives a flushed shared pool longer.
--   DBA_HIST_SQLSTAT — AWR-backed history. Defaults to 8 days retention.
--
-- Use V$SQLAREA for "what's hot right now". Use DBA_HIST_SQLSTAT when the
-- complaint is "yesterday morning was bad" and the offending SQL has aged
-- out of the cursor cache.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1) Top-10 from V$SQLAREA by TOTAL elapsed time.
--
-- elapsed_time is in microseconds. Divide by 1000 for ms, by 1e6 for sec.
-- Filter by parsing_schema_name = USER to scope to your app schema.
-- -----------------------------------------------------------------------------
SELECT *
FROM (
    SELECT
        SUBSTR(sql_text, 1, 120)                          AS sql_text,
        executions,
        ROUND(elapsed_time / 1000)                        AS total_elapsed_ms,
        ROUND(elapsed_time / NULLIF(executions, 0) / 1000, 2)
                                                          AS mean_elapsed_ms,
        ROUND(buffer_gets / NULLIF(executions, 0), 0)     AS lio_per_exec,
        ROUND(disk_reads  / NULLIF(executions, 0), 0)     AS pio_per_exec,
        rows_processed,
        sql_id
    FROM   v$sqlarea
    WHERE  parsing_schema_name = USER
      AND  UPPER(sql_text) NOT LIKE '%V$SQL%'
      AND  UPPER(sql_text) NOT LIKE '%DBMS_STATS%'
    ORDER  BY elapsed_time DESC
)
WHERE rownum <= 10;


-- -----------------------------------------------------------------------------
-- 2) Top-10 by MEAN elapsed (per execution) — individually slow queries.
-- -----------------------------------------------------------------------------
SELECT *
FROM (
    SELECT
        SUBSTR(sql_text, 1, 120)                          AS sql_text,
        executions,
        ROUND(elapsed_time / NULLIF(executions, 0) / 1000, 2)
                                                          AS mean_ms,
        ROUND(buffer_gets  / NULLIF(executions, 0))       AS lio_per_exec,
        sql_id
    FROM   v$sqlarea
    WHERE  parsing_schema_name = USER
      AND  executions > 5                                 -- ignore one-offs
    ORDER  BY elapsed_time / NULLIF(executions, 0) DESC
)
WHERE rownum <= 10;


-- -----------------------------------------------------------------------------
-- 3) Top-10 by BUFFER GETS per execution — the "doing too much work" smell.
--
-- buffer_gets are logical I/O. Even when cached, a query that touches 1M
-- blocks per execution is doing a Full Table Scan and starving everyone
-- else's working set.
-- -----------------------------------------------------------------------------
SELECT *
FROM (
    SELECT
        SUBSTR(sql_text, 1, 120)                          AS sql_text,
        executions,
        ROUND(buffer_gets / NULLIF(executions, 0))        AS lio_per_exec,
        ROUND(disk_reads  / NULLIF(executions, 0))        AS pio_per_exec,
        sql_id
    FROM   v$sqlarea
    WHERE  parsing_schema_name = USER
      AND  executions > 0
    ORDER  BY buffer_gets / NULLIF(executions, 0) DESC
)
WHERE rownum <= 10;


-- -----------------------------------------------------------------------------
-- 4) AWR — top SQL from yesterday's painful window.
--
-- DBA_HIST_SQLSTAT aggregates per snapshot interval (1h by default).
-- Join to DBA_HIST_SQLTEXT for the text and DBA_HIST_SNAPSHOT for the times.
--
-- NOTE: AWR retention defaults to 8 days. If the bad query was 3 weeks ago,
--       this won't find it. Adjust DBMS_WORKLOAD_REPOSITORY.MODIFY_SNAPSHOT_SETTINGS
--       in advance for systems where post-mortem investigations are common.
-- -----------------------------------------------------------------------------
SELECT *
FROM (
    SELECT
        s.sql_id,
        SUBSTR(t.sql_text, 1, 120)                       AS sql_text,
        SUM(s.executions_delta)                          AS total_execs,
        ROUND(SUM(s.elapsed_time_delta) / 1e6, 2)        AS total_elapsed_sec,
        ROUND(
            SUM(s.elapsed_time_delta)
            / NULLIF(SUM(s.executions_delta), 0) / 1000,
            2
        )                                                AS mean_ms,
        ROUND(SUM(s.buffer_gets_delta)
              / NULLIF(SUM(s.executions_delta), 0))      AS lio_per_exec
    FROM       dba_hist_sqlstat   s
    JOIN       dba_hist_snapshot  snap
               ON snap.snap_id  = s.snap_id
              AND snap.instance_number = s.instance_number
    JOIN       dba_hist_sqltext   t
               ON t.sql_id = s.sql_id
              AND t.dbid   = s.dbid
    WHERE      snap.end_interval_time > SYSDATE - 1     -- last 24h
      AND      s.parsing_schema_name  = USER
    GROUP BY   s.sql_id, SUBSTR(t.sql_text, 1, 120)
    ORDER BY   SUM(s.elapsed_time_delta) DESC
)
WHERE rownum <= 10;


-- -----------------------------------------------------------------------------
-- 5) Find unindexed foreign keys.
--
-- Same logic as the Postgres equivalent: every FK ideally has an index on the
-- referencing column. Without it, the parent UPDATE/DELETE acquires a table-
-- level lock on the child (the famous "ORA-00060 deadlock from unindexed FK").
-- Uses USER_* views — swap to DBA_* if you have the grant.
-- -----------------------------------------------------------------------------
SELECT
    c.table_name,
    c.constraint_name,
    cc.column_name,
    c.r_constraint_name                          AS references_constraint,
    'CREATE INDEX '
        || c.constraint_name || '_idx ON '
        || c.table_name || ' (' || cc.column_name || ');'   AS suggested_ddl
FROM   user_constraints  c
JOIN   user_cons_columns cc
       ON cc.constraint_name = c.constraint_name
      AND cc.position        = 1
WHERE  c.constraint_type = 'R'
  AND  NOT EXISTS (
      SELECT 1
      FROM   user_ind_columns ic
      WHERE  ic.table_name      = c.table_name
        AND  ic.column_name     = cc.column_name
        AND  ic.column_position = 1
  )
ORDER  BY c.table_name, c.constraint_name;


-- -----------------------------------------------------------------------------
-- 6) Get the execution plan for one suspect sql_id.
--
-- Copy the sql_id from the queries above and feed it here. DBMS_XPLAN.DISPLAY_CURSOR
-- shows the *actual* plan used (not the EXPLAIN PLAN guess), with E-Rows vs A-Rows
-- if the cursor was run with the GATHER_PLAN_STATISTICS hint.
-- -----------------------------------------------------------------------------
-- SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR('&sql_id', NULL, 'ALLSTATS LAST'));
