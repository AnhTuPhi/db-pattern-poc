# 20 - DB Migration Rollback (the "always-forward" pattern)

> Flyway can't "rollback" a migration.
> Liquibase technically can, but you shouldn't rely on it.
> The production-grade pattern is **always-forward**:
> never edit, never undo — write a new migration that fixes the bad one.

## Thesis

Schema migrations are not application code. They're **physical
transformations** of the database. A "rollback" of a migration is not
"git revert + redeploy" — the data has already changed shape. The
operations that put the data in the new shape may not be reversible
without loss (DROP COLUMN, TRUNCATE, ALTER TYPE), and even when they
are, the rollback steps are their own migration that has to be
written, tested, and applied.

The production pattern:

1. **NEVER edit a migration that has been applied anywhere.** Doing so
   changes the checksum; every environment that already ran it will
   fail to validate on the next deploy.
2. **NEVER write a "down" script.** It implies a button you can push.
   There is no button. There is only "deploy another migration".
3. **When you need to undo a bad migration, write a NEW migration**
   that brings the schema to the desired state. This is "always
   forward". The history shows: V3 broke it, V4 fixed it. The
   production database is correct. So is QA. So is the next
   environment you deploy to.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 20-db-migration-rollback -am spring-boot:run
```

App boots on **:8220**. On startup Flyway runs V1 → V2 → V3 → V4, then
applies R__ (the repeatable view).

## Run order

```bash
# 1. What did Flyway apply?
curl 'localhost:8220/flyway/history' | jq

# 2. What does Flyway SEE on the classpath vs. what's applied?
curl 'localhost:8220/flyway/info' | jq

# 3. What does the product table look like now?
curl 'localhost:8220/flyway/describe' | jq

# 4. (Optional) re-run migrate (no-op if all applied).
curl -X POST 'localhost:8220/flyway/migrate' | jq
```

## The migration files

```
src/main/resources/db/migration/
├── V1__create_product.sql              # initial schema + 3 rows
├── V2__add_currency.sql                # nullable add + constant default
├── V3__oops_typo_in_column.sql         # WRONG: 'wieght_grams' — we leave it
├── V4__fix_typo_weight_column.sql      # ALWAYS-FORWARD fix: add right name, copy, drop wrong
└── R__refresh_product_view.sql         # REPEATABLE view definition
```

**V** = versioned, applied exactly once, ordered by version number.
**R** = repeatable, re-applied any time its checksum changes, after all
pending V migrations.

### Reading V3 + V4 together

V3 shipped with a typo:

```sql
alter table product add column wieght_grams int;
```

We do not edit V3. We write V4:

```sql
alter table product add column weight_grams int;       -- correct
update product set weight_grams = wieght_grams;        -- carry data over
alter table product drop column wieght_grams;          -- remove the typo
```

After V4, the schema looks correct. The history shows the truth: V3
introduced a typo, V4 fixed it. Every environment — dev, staging,
prod — goes through the same sequence and arrives at the same final
state.

## Flyway's state machine

The whole thing is one table:

```sql
SELECT installed_rank, version, description, script, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

| installed_rank | version | description           | script                    | success |
|----------------|---------|-----------------------|---------------------------|---------|
| 1              | 1       | create product        | V1__create_product.sql    | t       |
| 2              | 2       | add currency          | V2__add_currency.sql      | t       |
| 3              | 3       | oops typo in column   | V3__oops_typo_in_column.sql | t     |
| 4              | 4       | fix typo weight column| V4__fix_typo_weight_column.sql | t  |
| 5              | (null)  | refresh product view  | R__refresh_product_view.sql | t     |

- `version` NULL → repeatable migration.
- `checksum` is computed at apply time. If you EDIT V3 after this row
  is written, the next Flyway startup compares the checksum and
  refuses to start. The error is loud and clear. **This is a feature.**
- `success=false` rows can exist if a migration aborted mid-way on a
  DB that doesn't support transactional DDL (looking at you, MySQL).
  Postgres wraps DDL in a tx, so partial-success rows are rare.

## Checksum failures and `flyway repair`

You will run into this. Someone edits V3 (maybe to add a comment),
production goes red. Flyway shows:

```
Validate failed: Migration checksum mismatch for migration version 3
  -> Applied to database : 1932482736
  -> Resolved locally    : -1746521099
```

You have two choices:

1. **Revert the edit** — restore V3 to its checksum-original state.
   Re-deploy. Best path 95% of the time.
2. **`flyway repair`** — rewrites the checksum in
   `flyway_schema_history` to match the current file contents. Use
   only when the edit was genuinely safe (a comment, whitespace).
   Run from a controlled environment, not from CI, not in panic.

Never bypass with `flyway.validateOnMigrate=false`. That hides the
problem permanently.

## Versioned (V) vs. Repeatable (R) migrations

| Aspect              | V — versioned                              | R — repeatable                                 |
|---------------------|--------------------------------------------|------------------------------------------------|
| Applied             | Exactly once, ordered by version           | Whenever checksum changes, after V migrations  |
| Use for             | Structural changes (CREATE, ALTER, DROP)   | Definitions (views, functions, procs, triggers)|
| Editing after apply | **Forbidden** — checksum mismatch          | **Expected** — that's how you update them      |
| Naming              | `V1__desc.sql`, `V1.1__desc.sql`           | `R__desc.sql` (no version)                     |
| Order               | V version numbers asc                      | Filename alphabetical                          |

The right rule: **anything that creates data state goes in V; anything
that defines a server-side object goes in R**. Views and functions are
R because "the file is the source of truth". Tables are V because
"history matters".

## Patterns to follow

```sql
-- ✅ V101__add_user_status_column.sql
alter table users add column status text;

-- ✅ V102__backfill_user_status.sql  (chunked update is in app code, not SQL)
-- ✅ V103__user_status_not_null.sql
alter table users alter column status set not null;
```

Three small migrations beat one big one. Each is shorter, more
reviewable, and bounds the AccessExclusiveLock window.

## Patterns to avoid

```sql
-- ❌ V99__just_a_typo_fix.sql       — you can never EDIT this V99
-- ❌ V99.1__forgot_an_index.sql      — works in Flyway, but smells
-- ❌ V99__big_combined_change.sql    — one mistake blocks the whole thing

-- ❌ DOWN migrations
-- Don't write them. They imply a button you don't really have. The
-- production "rollback" is a feature flag (m18) + a forward migration.
```

## Production checklist

| Symptom                                              | Likely cause                              | Fix                                                  |
|------------------------------------------------------|-------------------------------------------|------------------------------------------------------|
| `Validate failed: checksum mismatch`                 | Someone edited an applied V migration     | Revert the edit, or `flyway repair` if change is safe.|
| `Migration V123 failed` blocks all deploys           | DDL crashed mid-way                       | Fix the failing SQL; clean up the failed row from history; redeploy. |
| Want to "rollback" prod schema                        | Bad migration shipped                     | Always-forward: write a NEW migration that reverses. |
| Hot table locked for hours on ALTER                  | DDL is not online (see m19)               | Split into add-nullable, backfill-chunked, set-not-null. |
| Multiple devs add V123 at same time                  | Race in PR merge                          | Renumber in the LATER PR; CI should catch the collision. |
| Repeatable migration not re-applying                 | Checksum unchanged                        | Touch a comment to bump checksum; verify the change. |

## Files

```
src/main/java/com/claude/dbpoc/m20/
├── Application.java
├── service/
│   └── MigrationService.java       # history, info, describe, migrate
└── web/
    └── MigrationController.java    # /flyway/{history,info,describe,migrate}

src/main/resources/db/migration/
├── V1__create_product.sql
├── V2__add_currency.sql
├── V3__oops_typo_in_column.sql      # the bad one — kept as-is
├── V4__fix_typo_weight_column.sql   # always-forward fix
└── R__refresh_product_view.sql      # repeatable view definition
```

## Related modules

- **[m18 - zero-downtime-migration](../18-db-zero-downtime-migration/)**
  — expand/contract pattern, the structural shape of any non-trivial
  migration.
- **[m19 - online-ddl](../19-db-online-ddl/)** — which ALTER
  statements are safe to write inside a Flyway V file vs. need a
  pg_repack / external tool.
