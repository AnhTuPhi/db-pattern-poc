-- R: REPEATABLE migration. Re-applied EVERY TIME its checksum changes.
-- Use for objects that are CONTENT — views, functions, triggers,
-- materialized view definitions, comments — where "the latest version
-- wins" is the right semantic.
--
-- Repeatable migrations run AFTER all pending versioned migrations.
-- Edit this file freely; Flyway tracks the checksum and re-applies on
-- change.

create or replace view product_summary as
select
    id,
    sku,
    name,
    price_cents / 100.0 as price,
    currency,
    weight_grams
from product;
