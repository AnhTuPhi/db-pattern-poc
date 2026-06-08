-- ---------------------------------------------------------------------------
-- Module 04 schema (Postgres) — orders table tailored to three cardinality demos.
--
-- Why this exact shape:
--   - country_code: a low-cardinality column with extreme skew (~90% 'US').
--     Used to show that the planner picks Seq Scan vs Index Scan correctly when
--     stats are fresh, and gets it wrong when they aren't.
--   - region: deliberately CORRELATED with country_code. 'CALIFORNIA' implies
--     country_code='US'; 'BAVARIA' implies country_code='DE'; etc. Without
--     extended stats, Postgres multiplies the independent selectivities and
--     underestimates the matching row count by orders of magnitude.
--   - customer_id, amount, created_at: realistic noise + an indexed column for
--     spot-checks. Not central to the lessons.
--
-- All DDL is idempotent — schema.sql runs every boot, and SeedController also
-- assumes it can re-run without complaint.
-- ---------------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS m04_cardinality;
SET search_path = m04_cardinality;

CREATE TABLE IF NOT EXISTS orders (
    id           BIGSERIAL PRIMARY KEY,
    country_code VARCHAR(2)    NOT NULL,
    region       VARCHAR(20)   NOT NULL,
    customer_id  BIGINT        NOT NULL,
    amount       NUMERIC(12,2) NOT NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT now()
);

-- Index on country_code alone: lets the planner pick Index Scan for rare values
-- (like 'AQ') and (correctly) skip it for common values (like 'US').
CREATE INDEX IF NOT EXISTS idx_orders_country
    ON orders (country_code);

-- Composite (country_code, region): the index the planner WANTS to use for the
-- correlation demo. Without extended stats it estimates the matching rows too
-- low, may pick this index, and then under-allocates work memory etc.
CREATE INDEX IF NOT EXISTS idx_orders_country_region
    ON orders (country_code, region);

-- customer_id: high-cardinality, used for the "stats look fine" baseline scans.
CREATE INDEX IF NOT EXISTS idx_orders_customer
    ON orders (customer_id);
