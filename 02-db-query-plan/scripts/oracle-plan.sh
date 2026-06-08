#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Oracle equivalent of pg-plan.sh.
#
# Requires the Oracle docker profile up + sqlplus available locally OR via
# `docker exec`. We default to docker exec so you don't need an Instant Client.
# Override with SQLPLUS_CMD="sqlplus -S appuser/AppUser123@FREEPDB1" to use
# a local install.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p out
OUT="out/oracle-plan-$(date +%Y%m%d-%H%M%S).txt"

SQLPLUS_CMD="${SQLPLUS_CMD:-docker exec -i db-poc-oracle sqlplus -S appuser/AppUser123@FREEPDB1}"

${SQLPLUS_CMD} <<'SQL' | tee "${OUT}"
SET PAGESIZE 200
SET LINESIZE 200
SET SERVEROUTPUT ON
SET TIMING ON

PROMPT ============================================================
PROMPT  1. TABLE ACCESS FULL — Oracle's Seq Scan equivalent
PROMPT     WHERE status = 'PAID'  (~70% of rows)
PROMPT ============================================================
EXPLAIN PLAN SET STATEMENT_ID = 'm02_1' FOR
SELECT * FROM orders WHERE status = 'PAID';
SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE','m02_1','TYPICAL'));

PROMPT ============================================================
PROMPT  2. INDEX RANGE SCAN — Postgres' Index Scan
PROMPT     WHERE customer_id = 42
PROMPT ============================================================
EXPLAIN PLAN SET STATEMENT_ID = 'm02_2' FOR
SELECT * FROM orders WHERE customer_id = 42;
SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE','m02_2','TYPICAL'));

PROMPT ============================================================
PROMPT  3. INLIST ITERATOR — Postgres' Bitmap Heap Scan equivalent
PROMPT ============================================================
EXPLAIN PLAN SET STATEMENT_ID = 'm02_3' FOR
SELECT * FROM orders WHERE customer_id IN (1,2,3,4,5,6,7,8,9,10);
SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE','m02_3','TYPICAL'));

PROMPT ============================================================
PROMPT  4. With ALLSTATS LAST — actual vs estimated rows
PROMPT     Requires GATHER_PLAN_STATISTICS hint (executes the query)
PROMPT ============================================================
SELECT /*+ GATHER_PLAN_STATISTICS */ COUNT(*) FROM orders WHERE customer_id = 42;
SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(format => 'ALLSTATS LAST'));

PROMPT ============================================================
PROMPT  5. Force FULL vs INDEX with hints (no session knob in Oracle)
PROMPT ============================================================
EXPLAIN PLAN SET STATEMENT_ID = 'm02_5a' FOR
SELECT /*+ FULL(orders) */ * FROM orders WHERE customer_id = 42;
SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE','m02_5a','TYPICAL'));

EXPLAIN PLAN SET STATEMENT_ID = 'm02_5b' FOR
SELECT /*+ INDEX(orders idx_orders_customer) */ * FROM orders WHERE customer_id = 42;
SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE','m02_5b','TYPICAL'));

PROMPT ============================================================
PROMPT  6. Stats freshness — DBMS_STATS view
PROMPT ============================================================
SELECT table_name, num_rows, last_analyzed FROM user_tab_statistics WHERE table_name = 'ORDERS';

EXIT
SQL

echo ""
echo "==> Saved plan output to: ${OUT}"
