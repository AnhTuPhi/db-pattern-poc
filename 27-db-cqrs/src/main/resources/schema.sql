create schema if not exists m27_cqrs;
set search_path = m27_cqrs;

-- WRITE MODEL — normalized, JPA-mapped.
create table if not exists orders (
  id           bigserial primary key,
  user_id      bigint not null,
  total        numeric(18,2) not null,
  status       text not null,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

create index if not exists orders_user_idx on orders(user_id);

-- OUTBOX — events to be projected into the read model.
-- The write transaction inserts here ATOMICALLY with the entity write.
-- The poller drains in commit order (sorted by id).
create table if not exists outbox_events (
  id            bigserial primary key,
  aggregate_id  bigint not null,
  event_type    text not null,
  payload       jsonb not null,
  created_at    timestamptz not null default now(),
  processed_at  timestamptz,
  attempts      int not null default 0
);
create index if not exists outbox_unprocessed_idx
  on outbox_events(id) where processed_at is null;

-- READ MODEL — denormalized for the "give me a user's orders summary" query.
-- Stays separate from the write model on purpose: different schema,
-- different indexes, eventually consistent.
create table if not exists user_order_summary (
  user_id              bigint primary key,
  order_count          bigint not null default 0,
  total_revenue        numeric(18,2) not null default 0,
  last_order_at        timestamptz,
  last_event_id        bigint not null default 0   -- highest outbox id applied (idempotency)
);
