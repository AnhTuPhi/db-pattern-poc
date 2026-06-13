create schema if not exists m25_cache;

set search_path = m25_cache;

create table if not exists product (
  id    bigserial primary key,
  sku   text not null,
  name  text not null,
  price numeric(18,2) not null
);
