-- ---------------------------------------------------------------------------
-- Module 02 schema (Oracle) — mirrors schema-pg.sql so the same workload runs
-- against both engines and the plan output can be compared side-by-side.
--
-- Differences forced by Oracle:
--   - No BIGSERIAL: we use a sequence + identity column (12c+).
--   - No SCHEMA-per-module convention; the appuser owns this directly.
--   - DDL isn't transactional, so we wrap CREATE in a PL/SQL block that
--     swallows "name is already used by an existing object" (ORA-00955).
-- ---------------------------------------------------------------------------

BEGIN
  EXECUTE IMMEDIATE '
    CREATE TABLE orders (
      id          NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
      customer_id NUMBER(19)   NOT NULL,
      status      VARCHAR2(20) NOT NULL,
      total       NUMBER(12,2) NOT NULL,
      created_at  TIMESTAMP    DEFAULT SYSTIMESTAMP NOT NULL
    )';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 THEN RAISE; END IF;  -- ORA-00955: object already exists
END;
/

BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX idx_orders_customer ON orders(customer_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
