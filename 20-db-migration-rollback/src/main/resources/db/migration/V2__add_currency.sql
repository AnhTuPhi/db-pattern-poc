-- V2: add a currency column with a safe default.
-- This is the "good" migration: nullable add, constant default, no
-- table rewrite. See m19 for why this matters on large tables.

alter table product
    add column currency text not null default 'USD';
