#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Raw SQL walkthrough — the workflow a DBA would actually use at the psql
# prompt. The Java endpoints in this module wrap exactly these queries, but
# at 2 AM in an incident you'll be using psql, not curl.
#
# Run with:  ./scripts/pg-plan.sh
# Output:    saved under ./scripts/out/<timestamp>.txt
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p out
OUT="out/pg-plan-$(date +%Y%m%d-%H%M%S).txt"

PGPASSWORD="${PGPASSWORD:-AppUser123}" \
psql -h localhost -U appuser -d appdb -v ON_ERROR_STOP=1 <<'SQL' | tee "${OUT}"

\timing on
\pset format aligned
SET search_path TO m02_query_plan;

\echo ============================================================
\echo  1. Seq Scan — WHERE status = 'PAID' (~70% of rows)
\echo ============================================================
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM orders WHERE status = 'PAID';

\echo ============================================================
\echo  2. Index Scan — WHERE customer_id = 42 (highly selective)
\echo ============================================================
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM orders WHERE customer_id = 42;

\echo ============================================================
\echo  3. Bitmap Heap Scan — IN-list of 10 customer_ids
\echo ============================================================
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM orders WHERE customer_id IN (1,2,3,4,5,6,7,8,9,10);

\echo ============================================================
\echo  4. Index Only Scan — covered by idx_orders_customer
\echo ============================================================
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT id, customer_id FROM orders WHERE customer_id = 42;

\echo ============================================================
\echo  5. Forcing a plan — disable Seq Scan and re-EXPLAIN
\echo ============================================================
SET enable_seqscan = off;
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM orders WHERE status = 'PAID';
SET enable_seqscan = on;

\echo ============================================================
\echo  6. Stats freshness check (planner is only as good as its stats)
\echo ============================================================
SELECT relname, n_live_tup, n_dead_tup, last_analyze, last_autoanalyze
  FROM pg_stat_user_tables
 WHERE relname = 'orders';

SQL

echo ""
echo "==> Saved plan output to: ${OUT}"
