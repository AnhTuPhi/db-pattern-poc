-- Loaded on first Postgres start. Enables the extensions every diagnostic POC needs.

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gin;

-- Schema per POC module so they can coexist without stepping on each other.
CREATE SCHEMA IF NOT EXISTS m01_indexing;
CREATE SCHEMA IF NOT EXISTS m02_query_plan;
CREATE SCHEMA IF NOT EXISTS m03_slow_query;
CREATE SCHEMA IF NOT EXISTS m04_cardinality;
CREATE SCHEMA IF NOT EXISTS m05_n_plus_one;
CREATE SCHEMA IF NOT EXISTS m06_fetch_strategies;
CREATE SCHEMA IF NOT EXISTS m07_flush_cascade;
CREATE SCHEMA IF NOT EXISTS m08_batch_insert;
CREATE SCHEMA IF NOT EXISTS m09_querydsl;
CREATE SCHEMA IF NOT EXISTS m10_isolation;

GRANT ALL ON SCHEMA m01_indexing,        m02_query_plan,    m03_slow_query,
                   m04_cardinality,      m05_n_plus_one,    m06_fetch_strategies,
                   m07_flush_cascade,    m08_batch_insert,  m09_querydsl,
                   m10_isolation
      TO appuser;
