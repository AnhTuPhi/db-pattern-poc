-- V4: ALWAYS-FORWARD fix for V3's typo.
--
-- We didn't edit V3. We don't have a "down". We write a NEW migration
-- that takes the schema from where V3 left it to where we want it.
--
-- In production this is usually a SEQUENCE of safe steps (see m18 for
-- the expand/contract pattern):
--
--   1. Add the correctly-named column.
--   2. Copy data from the misspelled column.
--   3. Drop the misspelled column.
--
-- Since this column is freshly added in V3 and has no data and no app
-- references yet, we can do all three steps here in one migration. On
-- a column that's been live for weeks, you'd split steps 2 and 3 over
-- multiple deploys so app code can catch up.

alter table product add column weight_grams int;

update product set weight_grams = wieght_grams;

alter table product drop column wieght_grams;
