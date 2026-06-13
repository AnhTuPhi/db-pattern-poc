-- V1: the original schema.
-- Versioned migrations (V###__) are applied exactly once. Flyway
-- records the checksum in flyway_schema_history. NEVER edit this
-- file after it's been applied anywhere — the checksum will mismatch
-- and Flyway will refuse to start until you `flyway repair`.

create table product (
    id          bigserial primary key,
    sku         text   not null unique,
    name        text   not null,
    price_cents int    not null check (price_cents >= 0)
);

insert into product(sku, name, price_cents) values
    ('SKU-001', 'Pen',     150),
    ('SKU-002', 'Pencil',  100),
    ('SKU-003', 'Eraser',   75);
