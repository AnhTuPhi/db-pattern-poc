create schema if not exists m29_hybrid;
set search_path = m29_hybrid;

-- The "hybrid" shape: structured spine, document leaves.
--
-- `customer` — has the fields every query touches as REAL COLUMNS:
--   id, tenant_id, email, created_at. These are FK targets, they're
--   indexed, they're NOT NULL, and the planner has stats on them.
--
-- Then a `profile jsonb` column for the bits that vary per customer:
--   preferred contact channel, marketing consent flags, free-form
--   tags, third-party integration ids. Adding one is free; nobody
--   has to coordinate a migration.
create table if not exists customer (
  id          bigserial primary key,
  tenant_id   bigint not null,
  email       text   not null,
  created_at  timestamptz not null default now(),
  profile     jsonb  not null default '{}'::jsonb,
  unique (tenant_id, email)
);
create index if not exists customer_tenant_idx     on customer (tenant_id);
create index if not exists customer_profile_gin    on customer using gin (profile jsonb_path_ops);

-- `customer_order` — same pattern, more interesting.
--   id, customer_id (FK), status, total, placed_at are columns.
--   `items` is a JSONB ARRAY of line items — the shape of a line item
--   varies by product type (a book has ISBN; an electronic has
--   serial+warranty; a digital download has license_key+expiry).
--   Forcing one schema across all of those gives you either a wide
--   sparse table or a polymorphic-table mess. JSONB just stores
--   each line as it is.
create table if not exists customer_order (
  id           bigserial primary key,
  customer_id  bigint not null references customer(id) on delete cascade,
  status       text   not null,
  total        numeric(18,2) not null,
  placed_at    timestamptz not null default now(),
  items        jsonb  not null,
  check (jsonb_typeof(items) = 'array')
);
create index if not exists order_customer_idx on customer_order (customer_id);
create index if not exists order_status_idx   on customer_order (status);
create index if not exists order_items_gin    on customer_order using gin (items jsonb_path_ops);
