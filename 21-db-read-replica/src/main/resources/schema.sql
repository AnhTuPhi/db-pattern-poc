-- Seed primary and replica schemas with the same Account table.
-- Spring runs this against the PRIMARY (default) DataSource at startup
-- via `spring.sql.init.mode=always`, but we use both schemas in
-- application code via direct connections — see the schema-bootstrap
-- in ReplicaService.seed().

create schema if not exists m21_primary;
create schema if not exists m21_replica;

create table if not exists m21_primary.account (
  id      bigserial primary key,
  owner   text not null,
  balance numeric(18,2) not null
);

create table if not exists m21_replica.account (
  id      bigint primary key,
  owner   text not null,
  balance numeric(18,2) not null
);
