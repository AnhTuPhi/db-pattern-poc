-- ---------------------------------------------------------------------------
-- Module 02 schema (Postgres) — single 'orders' table with one secondary index.
--
-- Why this shape:
--   The whole point of the module is to show how the SAME query against the
--   SAME data picks DIFFERENT plans depending on selectivity. To do that we
--   need:
--     - A table big enough that Seq Scan is a real cost (1M rows in seeder).
--     - One non-PK column with a B-tree index (customer_id) so we can compare
--       Index Scan vs Seq Scan vs Bitmap Heap Scan against the same column.
--     - A 'status' column with skewed distribution (~70% PAID) so a predicate
--       on status forces a Seq Scan even though we could add an index.
--
-- All DDL is idempotent: this script runs every time SeedController is called.
-- ---------------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS m02_query_plan;
SET search_path = m02_query_plan;

CREATE TABLE IF NOT EXISTS orders (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    total       NUMERIC(12,2) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- The ONE secondary index we'll demo against. Naming convention: idx_<table>_<col>.
-- This is a plain B-tree — Postgres' default and the one the planner reasons
-- about most often. Don't add more indexes here: part of the lesson is seeing
-- the planner pick Seq Scan because there's no helpful index for `status`.
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_id);
