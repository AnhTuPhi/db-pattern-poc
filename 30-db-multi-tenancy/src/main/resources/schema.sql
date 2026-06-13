-- ============================================================
-- STRATEGY 1 : SHARED SCHEMA + tenant_id  (+  Row-Level Security)
-- ============================================================
create schema if not exists m30_shared;
set search_path = m30_shared;

create table if not exists product (
  id          bigserial primary key,
  tenant_id   bigint not null,
  sku         text   not null,
  name        text   not null,
  price       numeric(18,2) not null,
  unique (tenant_id, sku)
);
create index if not exists product_tenant_idx on product (tenant_id);

-- Row-Level Security on the shared table.
--
-- Once enabled and a policy is set, every SELECT/UPDATE/DELETE
-- carries an IMPLICIT `WHERE tenant_id = current_setting('app.tenant_id')::bigint`
-- added by the planner. App code can forget to add the filter and
-- still be safe (subject to the role not being BYPASSRLS — superuser
-- bypasses it, so `appuser` is correct here).
alter table product enable row level security;
alter table product force  row level security;  -- also apply to table owner

drop policy if exists product_tenant_isolation on product;
create policy product_tenant_isolation on product
  using       (tenant_id = current_setting('app.tenant_id', true)::bigint)
  with check  (tenant_id = current_setting('app.tenant_id', true)::bigint);

-- ============================================================
-- STRATEGY 2 : SCHEMA-PER-TENANT
-- ============================================================
-- One schema per tenant. The Java side flips `search_path`
-- per request via a routing DataSource. Same DDL replicated.
-- Bootstrap three tenants here; the service can spin up new
-- tenants at runtime via `create_tenant_schema(?)`.
create schema if not exists m30_t_1;
create schema if not exists m30_t_2;
create schema if not exists m30_t_3;

create table if not exists m30_t_1.product (
  id    bigserial primary key,
  sku   text not null unique,
  name  text not null,
  price numeric(18,2) not null
);
create table if not exists m30_t_2.product (
  id    bigserial primary key,
  sku   text not null unique,
  name  text not null,
  price numeric(18,2) not null
);
create table if not exists m30_t_3.product (
  id    bigserial primary key,
  sku   text not null unique,
  name  text not null,
  price numeric(18,2) not null
);

-- Helper for new-tenant onboarding from the app.
create or replace function m30_shared.create_tenant_schema(tid bigint)
returns void as $$
declare s text := format('m30_t_%s', tid);
begin
  execute format('create schema if not exists %I', s);
  execute format(
    'create table if not exists %I.product (' ||
    '  id bigserial primary key, sku text not null unique, ' ||
    '  name text not null, price numeric(18,2) not null)', s);
end;
$$ language plpgsql;
