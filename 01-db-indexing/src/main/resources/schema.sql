-- ----------------------------------------------------------------------------
-- m01_indexing: events table used to demonstrate each index variant.
--
-- The schema (m01_indexing) and required extensions (pg_trgm, btree_gin) are
-- created by docker/postgres-init.sql when the container first boots.
--
-- Indexes are NOT created here on purpose: every endpoint
-- (POST /indexes/btree, /covering, /partial, ...) creates exactly the index
-- it needs so the README can show a clean "bench → create → bench" diff.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS events (
    id            bigserial PRIMARY KEY,
    user_id       bigint        NOT NULL,
    event_type    varchar(40)   NOT NULL,
    status        varchar(20)   NOT NULL,
    amount        numeric(12,2) NOT NULL,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    payload       jsonb,
    search_text   text
);

-- ----------------------------------------------------------------------------
-- Reference: the indexes each endpoint will create (kept here for documentation).
--
-- CREATE INDEX idx_events_user_id            ON events (user_id);
-- CREATE INDEX idx_events_user_created       ON events (user_id, created_at DESC);
-- CREATE INDEX idx_events_user_covering      ON events (user_id) INCLUDE (status, amount);
-- CREATE INDEX idx_events_pending_partial    ON events (created_at DESC) WHERE status = 'PENDING';
-- CREATE INDEX idx_events_lower_search_text  ON events (LOWER(search_text));
-- CREATE INDEX idx_events_search_trgm        ON events USING GIN (search_text gin_trgm_ops);
-- ----------------------------------------------------------------------------
