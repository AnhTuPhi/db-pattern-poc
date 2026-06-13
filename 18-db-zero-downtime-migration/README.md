# 18 - DB Zero-Downtime Migration (expand/contract pattern)

> You can NEVER deploy app and schema atomically.
> Old app and new app WILL be live at the same time.
> The schema must work for BOTH at every step.

## Thesis

The naive migration — "rename `full_name` to `display_name` in one
deploy" — assumes app deploy and schema deploy happen simultaneously.
They don't. Even in a small fleet:

- Some pods are on old code reading `full_name` when you alter the
  column → they error.
- Or you alter first, deploy second → for that window new code looks
  for `display_name` which doesn't exist.
- Even with blue/green, there's a few seconds of overlap. That's enough
  to corrupt or error a high-traffic table.

The **expand/contract** pattern (sometimes called "parallel change")
splits the migration into 6 steps where at every step, **both** the
old code and the new code work correctly:

```
BASELINE   →  EXPAND   →  DUAL_WRITE  →  BACKFILL  →  DUAL_READ  →  SWITCH_READS  →  CONTRACT
full_name     +display    write both     copy old→new   read new       read+write     drop full_name
                                                        prefer new     new only
```

At every arrow, you can hold for hours. You only proceed when the
preceding step has been verified in production.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 18-db-zero-downtime-migration -am spring-boot:run
```

App boots on **:8218**.

## Run order (each step is one deploy in production)

```bash
# 0. Reset to baseline (users table, full_name only, 4 seeded rows).
curl -X POST 'localhost:8218/migration/reset' | jq
curl 'localhost:8218/migration/describe' | jq

# 1. EXPAND — add display_name (nullable, default null). Backward-compatible.
curl -X POST 'localhost:8218/migration/expand' | jq

# 2. DUAL_WRITE — new code writes BOTH columns. Old code unaffected.
curl -X POST 'localhost:8218/migration/dual-write?id=1&name=Ada%20Lovelace' | jq
curl 'localhost:8218/migration/describe' | jq

# 3. BACKFILL — copy old → new for rows where new is still NULL.
curl -X POST 'localhost:8218/migration/backfill' | jq

# 4. DUAL_READ — new code prefers display_name; old code still uses full_name.
curl 'localhost:8218/migration/dual-read' | jq

# 5. SWITCH_READS — new code writes ONLY display_name. Old code, if any
#    is still running, would now read STALE full_name. Window for rollback
#    to old code is closed.
curl -X POST 'localhost:8218/migration/switch-reads?id=1&name=Ada%20Loveless' | jq

# 6. CONTRACT — drop full_name. Migration complete.
curl -X POST 'localhost:8218/migration/contract' | jq
curl 'localhost:8218/migration/describe' | jq
```

## The six phases in detail

### Phase 1 — EXPAND
```sql
ALTER TABLE users ADD COLUMN display_name TEXT;        -- nullable, no default
```
Backward-compatible. Old code doesn't know it exists. New code can use
it if present, fall back if not. **Rollback: drop the column.**

⚠️ DO NOT add NOT NULL or a non-NULL default in this step. On large
tables that requires a full table rewrite, which takes an exclusive
lock. Use a nullable column + post-deploy backfill instead.

### Phase 2 — DUAL_WRITE
```java
// new code
update users set full_name = ?, display_name = ? where id = ?
```
Every write hits both columns. Old code (which writes only `full_name`)
is fine — `display_name` for that row stays NULL and will be backfilled.

**Rollback: stop writing display_name in new code; deploy.**

### Phase 3 — BACKFILL
```sql
UPDATE users SET display_name = full_name WHERE display_name IS NULL;
```
For tiny tables, one shot. For large tables, chunk it:

```sql
-- batch by id range to avoid one giant UPDATE
UPDATE users SET display_name = full_name
WHERE id BETWEEN 1 AND 10000 AND display_name IS NULL;
```

Or use `pg_repack` / `pt-online-schema-change` / `gh-ost`. The key is
that the backfill **cannot** lock the table — use chunks + commits or
an online tool.

**Rollback: TRUNCATE display_name. Old code is still authoritative.**

### Phase 4 — DUAL_READ
```sql
SELECT id, full_name, display_name,
       COALESCE(display_name, full_name) AS preferred
FROM users;
```
New code reads `COALESCE(display_name, full_name)` and writes both.
Old code reads `full_name` (still correct, dual-write keeps it up to
date). Both work.

This is **the safe window**. Hold here until canaries / metrics / your
gut tell you to advance.

### Phase 5 — SWITCH_READS
```java
// new code (no more dual-write)
update users set display_name = ? where id = ?
```
This is the step that **closes the rollback window**. After this
write, `full_name` for that row is stale. Old code would read the stale
value. Before doing this, you must be CERTAIN no old code is running:

- Check fleet status: every pod on the new image.
- Wait through any cache TTL / queue drain.
- Verify by query that no queries against `full_name` are happening:
  ```sql
  SELECT query FROM pg_stat_statements WHERE query LIKE '%full_name%';
  ```

**Rollback: deploy new code that does dual-write again. NOT instant —
you have to reconcile newly-stale `full_name` values from `display_name`.
That reconciliation is itself a tiny backfill.**

### Phase 6 — CONTRACT
```sql
ALTER TABLE users DROP COLUMN full_name;
```
The migration is complete. From here, `display_name` is the only column.

**Rollback: re-create the column and dual-write again. Painful.**

## The general pattern (for ANY change)

| Change                              | Expand                               | Contract                                |
|-------------------------------------|--------------------------------------|-----------------------------------------|
| Rename column                       | Add new col, dual-write              | Drop old col                            |
| Change data type (int → bigint)     | Add bigint col, dual-write           | Drop int col, rename                    |
| Add NOT NULL                        | Add nullable, backfill, then add NOT NULL constraint | (no contract)         |
| Split table                         | Add new table, dual-write            | Drop old columns from original          |
| Merge tables                        | Add columns to A, dual-write         | Drop B                                  |
| Add foreign key                     | Add column nullable, backfill, then ALTER ... ADD CONSTRAINT NOT VALID, VALIDATE later | — |

The pattern is always: **add new in a way that doesn't disturb old →
move data over → swap reads → remove old**.

## What about Flyway / Liquibase?

They orchestrate the **SQL** part. They don't orchestrate the
**code-deploy** part — that's still your responsibility. A clean
Flyway sequence for the rename:

```
V101__add_display_name.sql              -- Phase 1 (EXPAND)
V102__backfill_display_name.sql         -- Phase 3 (BACKFILL)
V103__drop_full_name.sql                -- Phase 6 (CONTRACT)
```

Phases 2/4/5 are application-code deploys, not Flyway migrations.
The numbering reflects which Flyway version each app deploy depends on.
See [m20 - migration-rollback](../20-db-migration-rollback/) for how
this interacts with always-forward.

## Production checklist

| Symptom                                              | Likely cause                              | Fix                                                  |
|------------------------------------------------------|-------------------------------------------|------------------------------------------------------|
| ALTER TABLE takes the table offline                  | Adding NOT NULL + non-null DEFAULT        | Add nullable first; backfill; THEN add NOT NULL.    |
| Old pods erroring on missing column                  | Deployed schema before app                | Always expand FIRST; deploy code that uses it second.|
| New pods erroring on missing column                  | Deployed app before schema                | Same — expand first.                                 |
| Data drift between full_name and display_name        | Dual-write missed somewhere               | Audit every write path; rerun backfill on diff rows.|
| Backfill UPDATE locked the table for hours           | Single unbounded UPDATE                   | Chunk by id range; commit between chunks.            |
| Postgres bloats during migration                     | Long backfill tx pins xmin                | Chunk; commit; see m14.                              |

## Files

```
src/main/java/com/claude/dbpoc/m18/
├── Application.java
├── service/
│   └── ExpandContractService.java   # one method per phase
└── web/
    └── MigrationController.java     # /migration/{reset,expand,dual-write,...}
```

## Related modules

- **[m14 - long-transaction](../14-db-long-transaction/)** — why
  backfill must be chunked.
- **[m19 - online-ddl](../19-db-online-ddl/)** — tools (pg_repack,
  pt-online-schema-change, gh-ost) for the parts of expand/contract
  that need physical table rewrites.
- **[m20 - migration-rollback](../20-db-migration-rollback/)** — why
  there's no "rollback button" for DB migrations and how always-forward
  reconciles.
