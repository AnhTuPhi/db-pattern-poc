-- =============================================================================
-- m03_slow_query schema — the "bait" for slow-query hunting.
--
-- Three tables. Each one is deliberately missing an index that an SRE would
-- spot in pg_stat_statements once a workload runs against it.
--
-- This file is executed on demand by /seed (NOT by spring.sql.init.mode=always)
-- so that we can keep it idempotent and only pay the row-generation cost once.
-- =============================================================================

-- Make sure pg_stat_statements is available. The extension itself lives in the
-- public schema; we just reference it from this schema's queries.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- ---------------------------------------------------------------------------
-- accounts: ~100k rows. Lookup table. Has a PK on id, that's it.
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS transactions;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS accounts;

CREATE TABLE accounts (
    id          BIGINT PRIMARY KEY,
    balance     NUMERIC(18, 2) NOT NULL,
    owner_name  TEXT           NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

INSERT INTO accounts (id, balance, owner_name, created_at)
SELECT  g,
        (random() * 100000)::numeric(18, 2),
        'owner_' || g,
        now() - (random() * interval '365 days')
FROM    generate_series(1, 100000) AS g;

-- ---------------------------------------------------------------------------
-- transactions: ~5M rows. THE FK on account_id has NO supporting index.
-- This is bait #1: WHERE account_id = ? will Seq Scan 5M rows every time,
-- showing up as a huge mean_exec_time in pg_stat_statements.
-- ---------------------------------------------------------------------------
CREATE TABLE transactions (
    id          BIGINT PRIMARY KEY,
    account_id  BIGINT      NOT NULL REFERENCES accounts(id),
    amount      NUMERIC(18, 2) NOT NULL,
    type        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO transactions (id, account_id, amount, type, created_at)
SELECT  g,
        ((random() * 99999)::bigint) + 1,
        (random() * 1000)::numeric(18, 2),
        (ARRAY['DEPOSIT', 'WITHDRAW', 'TRANSFER', 'FEE'])[1 + (random() * 3)::int],
        now() - (random() * interval '730 days')
FROM    generate_series(1, 5000000) AS g;

-- NOTE: deliberately NO index on transactions(account_id). This is the
-- classic "missing FK index" production smell.

-- ---------------------------------------------------------------------------
-- audit_log: ~1M rows. No compound index on (entity_type, entity_id) — the
-- pattern code routinely queries on. Bait #2.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_log (
    id           BIGINT PRIMARY KEY,
    entity_type  TEXT        NOT NULL,
    entity_id    BIGINT      NOT NULL,
    action       TEXT        NOT NULL,
    ts           TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO audit_log (id, entity_type, entity_id, action, ts)
SELECT  g,
        (ARRAY['account', 'transaction', 'user', 'session'])[1 + (random() * 3)::int],
        ((random() * 99999)::bigint) + 1,
        (ARRAY['CREATE', 'UPDATE', 'DELETE', 'VIEW'])[1 + (random() * 3)::int],
        now() - (random() * interval '180 days')
FROM    generate_series(1, 1000000) AS g;

-- NOTE: deliberately NO index on audit_log(entity_type, entity_id).
-- The well-known "missing-compound-index" production smell.

-- ---------------------------------------------------------------------------
-- Stats refresh so the planner has accurate numbers for EXPLAIN later.
-- ---------------------------------------------------------------------------
ANALYZE accounts;
ANALYZE transactions;
ANALYZE audit_log;
