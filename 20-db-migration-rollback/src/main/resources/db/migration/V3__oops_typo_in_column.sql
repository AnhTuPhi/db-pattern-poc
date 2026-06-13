-- V3: the "oops" — we meant to add `weight_grams` but a typo shipped
-- as `wieght_grams`. This is a real story: the PR was reviewed in 30
-- seconds, the migration ran, the column is now in production.
--
-- KEY POINT: We're going to LEAVE this file as-is.
-- Editing V3 would change its checksum. Every other environment that
-- already ran V3 would fail validation on next deploy. The
-- production-grade fix is to write a NEW migration (V4) that
-- forward-rolls the schema into the desired state.

alter table product
    add column wieght_grams int;
