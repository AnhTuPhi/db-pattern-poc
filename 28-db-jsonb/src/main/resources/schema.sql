create schema if not exists m28_jsonb;
set search_path = m28_jsonb;

-- Two tables for the same domain — a product catalog.
--
-- 1) `product_normalized` — every field has its own column. Constraints
--    are real; indexes are obvious; queries are SQL. The boring win.
create table if not exists product_normalized (
  id        bigserial primary key,
  sku       text not null,
  name      text not null,
  brand     text not null,
  price     numeric(18,2) not null,
  stock     int not null check (stock >= 0),
  category  text not null
);
create index if not exists pn_brand_idx    on product_normalized (brand);
create index if not exists pn_category_idx on product_normalized (category);

-- 2) `product_doc` — the "schemaless" version. Everything goes into a
--    JSONB column. We add a CHECK constraint to assert minimum shape;
--    in real life you might add more, or use the pg_jsonschema
--    extension to validate against a full JSON Schema.
create table if not exists product_doc (
  id   bigserial primary key,
  data jsonb not null,
  -- minimum guarantees the rest of the app can rely on
  check (jsonb_typeof(data->'sku')   = 'string'),
  check (jsonb_typeof(data->'price') = 'number')
);
