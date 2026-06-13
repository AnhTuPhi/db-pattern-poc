# 19 - DB Online DDL (the ALTER that doesn't take you down)

> Most DDL statements are "online" since PG11.
> The ones that aren't will take an AccessExclusiveLock and break production.
> Know the difference before you run the migration.

## Thesis

When you `ALTER TABLE` on a large hot table, Postgres takes a lock.
Which lock determines whether your service stays up:

| Lock                      | Blocks reads | Blocks writes |
|---------------------------|--------------|---------------|
| `ACCESS SHARE`            | no           | no            |
| `ROW SHARE`               | no           | no            |
| `ROW EXCLUSIVE`           | no           | no            |
| `SHARE UPDATE EXCLUSIVE`  | no           | no            |
| `SHARE`                   | no           | **yes**       |
| `SHARE ROW EXCLUSIVE`     | no           | **yes**       |
| `EXCLUSIVE`               | **yes** (sql)| **yes**       |
| `ACCESS EXCLUSIVE`        | **yes**      | **yes**       |

`ALTER TABLE` defaults to `ACCESS EXCLUSIVE`. For a few of the safe
operations (covered below), the lock is held for milliseconds —
metadata-only. For unsafe operations, it's held for the duration of a
full table rewrite, which on a 100GB table can be hours.

**The lock duration is the outage duration.**

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 19-db-online-ddl -am spring-boot:run
```

App boots on **:8219**.

## Run order

```bash
# 0. Seed 50k rows.
curl -X POST 'localhost:8219/ddl/seed?rows=50000' | jq

# 1. SAFE: ADD COLUMN with constant default — metadata only.
curl -X POST 'localhost:8219/ddl/add-column-safe' | jq
curl 'localhost:8219/ddl/describe' | jq

# 2. Reset.
curl -X POST 'localhost:8219/ddl/seed?rows=50000' | jq

# 3. UNSAFE: ADD COLUMN with volatile default — full rewrite.
curl -X POST 'localhost:8219/ddl/add-column-unsafe' | jq

# 4. CREATE INDEX CONCURRENTLY — does NOT block writes.
curl -X POST 'localhost:8219/ddl/create-index-concurrently' | jq
curl 'localhost:8219/ddl/describe' | jq
```

## What each endpoint proves

### `/ddl/add-column-safe`
```sql
ALTER TABLE users ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE';
```

Since PG11, a CONSTANT default is stored in
`pg_attribute.atthasmissing + attmissingval`. The default is **synthesized
on read** for existing rows. The table is NOT rewritten. Lock duration:
milliseconds, regardless of table size.

✅ This is what you want.

### `/ddl/add-column-unsafe`
```sql
ALTER TABLE users ADD COLUMN token uuid NOT NULL DEFAULT gen_random_uuid();
```

`gen_random_uuid()` is a **VOLATILE** function. Each row needs its own
evaluation. Postgres CAN'T store one default; it falls back to the
pre-PG11 behavior: **full table rewrite** with `AccessExclusiveLock`
held for the duration. On 50k rows it's noticeable. On 100M rows it's
hours of total outage.

❌ Outage trap.

The correct sequence:

```sql
-- 1. Add column nullable (fast, metadata only).
ALTER TABLE users ADD COLUMN token uuid;

-- 2. Backfill in chunks (commits between chunks → no long lock, no bloat).
DO $$
DECLARE max_id bigint := (select max(id) from users);
DECLARE batch  int    := 10000;
BEGIN
  FOR i IN 0..max_id BY batch LOOP
    UPDATE users SET token = gen_random_uuid()
    WHERE id >= i AND id < i + batch AND token IS NULL;
    COMMIT;
  END LOOP;
END $$;

-- 3. PG12+ fast NOT NULL via constraint then drop, or just SET NOT NULL.
ALTER TABLE users ALTER COLUMN token SET NOT NULL;

-- 4. Optionally set default for future rows.
ALTER TABLE users ALTER COLUMN token SET DEFAULT gen_random_uuid();
```

Each step is short. The big one (#2) doesn't hold a lock — chunked
UPDATE + commit.

### `/ddl/create-index-concurrently`
```sql
CREATE INDEX CONCURRENTLY users_email_idx ON users(email);
```

Two-phase build:

1. First pass: scan the table, add index entries for current rows. Takes
   `SHARE UPDATE EXCLUSIVE` — concurrent writes ARE allowed.
2. Second pass: wait for transactions that started before phase 1 to
   finish, then scan again for rows changed during phase 1.

The trade-offs:

- **CANNOT** run inside a transaction (it has to wait for old tx to
  finish; would deadlock).
- Slower than plain `CREATE INDEX` (two scans).
- If anything fails (duplicate key, OOM), leaves an **INVALID** index.
  Check with:
  ```sql
  SELECT indexrelid::regclass, indisvalid
  FROM pg_index
  WHERE indrelid = 'users'::regclass;
  ```
  Then `DROP INDEX` and retry.

Same rule for **REINDEX CONCURRENTLY** and **DROP INDEX CONCURRENTLY**
(PG12+).

## DDL quick reference (Postgres)

| Statement                                          | Lock                       | Rewrite? | Safe online? |
|----------------------------------------------------|----------------------------|----------|--------------|
| `ALTER TABLE ADD COLUMN x type`                    | AccessExclusive (short)    | no       | ✅           |
| `ALTER TABLE ADD COLUMN x type DEFAULT <const>`    | AccessExclusive (short)    | no PG11+ | ✅ PG11+     |
| `ALTER TABLE ADD COLUMN x type DEFAULT <volatile>` | AccessExclusive (long)     | **YES**  | ❌           |
| `ALTER TABLE ADD COLUMN x type NOT NULL` (no default) | AccessExclusive (long)  | **YES**  | ❌           |
| `ALTER TABLE DROP COLUMN x`                        | AccessExclusive (short)    | no       | ✅           |
| `ALTER TABLE ALTER COLUMN SET NOT NULL`            | AccessExclusive (scan)     | no (PG12+) | ⚠️ scans table |
| `ALTER TABLE ALTER COLUMN TYPE` (compatible)       | AccessExclusive (short)    | depends  | ⚠️           |
| `ALTER TABLE ADD CONSTRAINT ... NOT VALID`         | AccessExclusive (short)    | no       | ✅           |
| `ALTER TABLE VALIDATE CONSTRAINT`                  | ShareUpdateExclusive       | no       | ✅           |
| `CREATE INDEX`                                     | Share                      | no       | ❌ (blocks writes) |
| `CREATE INDEX CONCURRENTLY`                        | ShareUpdateExclusive       | no       | ✅           |
| `CREATE TABLE`                                     | none on existing tables    | no       | ✅           |
| `VACUUM`                                           | ShareUpdateExclusive       | no       | ✅           |
| `VACUUM FULL`                                      | AccessExclusive            | **YES**  | ❌           |
| `CLUSTER`                                          | AccessExclusive            | **YES**  | ❌           |

The patterns are:

- If the change is **metadata-only** in catalog, it's safe.
- If it requires reading every row (NOT NULL validation, type cast,
  index build), the question is **which lock** it takes during that
  scan. Most of the safe ones take ShareUpdateExclusive (allows DML).
- If it requires **rewriting** every row (VACUUM FULL, CLUSTER, ALTER
  TYPE incompatible, ADD COLUMN with volatile default), you need
  AccessExclusive for the duration. Use an **online tool** instead.

## When you need a real online tool

Some operations Postgres genuinely cannot do online. Examples:

- Reclaim bloat without `VACUUM FULL`.
- Change a column type in a way that requires re-encoding all rows.
- Rebuild a table physically clustered on a different column.

The tools:

### `pg_repack` (Postgres)
The standard. Strategy:

1. Create a **shadow** table with the desired layout.
2. Add **triggers** on the original to mirror INSERT/UPDATE/DELETE
   into the shadow.
3. Copy live rows into the shadow.
4. Catch up with the trigger backlog.
5. Briefly take AccessExclusiveLock to SWAP the table names.

Total downtime: the swap (milliseconds). Compatible with PG's MVCC.
Requires the `pg_repack` extension installed; needs ~2x disk for the
shadow during the operation.

### `pt-online-schema-change` (Percona, MySQL)
Same idea — shadow table + trigger-driven sync. The reference
implementation for MySQL.

### `gh-ost` (GitHub, MySQL)
Trigger-LESS. Reads the **binlog** to track changes instead of
triggers. Lower overhead on the source table, doesn't fight with
existing triggers. GitHub's standard for MySQL schema changes at
their scale.

### Oracle Online Redefinition (`DBMS_REDEFINITION`)
Oracle's built-in equivalent. Materialized-view-log driven instead of
triggers. Built into the database engine — no external tool. Locks
the table briefly at the START (snapshot) and END (swap).

## Production checklist

| Goal                                       | Postgres approach                                | MySQL                | Oracle                |
|--------------------------------------------|--------------------------------------------------|----------------------|-----------------------|
| Add column, constant default               | `ALTER TABLE ADD COLUMN ... DEFAULT 'x'`         | online (8.0+)        | inline               |
| Add column, computed default               | nullable add + chunked UPDATE + SET NOT NULL     | pt-osc / gh-ost      | DBMS_REDEFINITION    |
| Drop column                                | `ALTER TABLE DROP COLUMN`                        | online               | inline               |
| Change column type, compatible             | `ALTER TABLE ALTER COLUMN TYPE`                  | depends              | inline               |
| Change column type, requires re-encode     | expand/contract (m18) + chunked backfill         | pt-osc / gh-ost      | DBMS_REDEFINITION    |
| Add index on hot table                     | `CREATE INDEX CONCURRENTLY`                      | `ALGORITHM=INPLACE` or pt-osc | online add idx |
| Add NOT NULL                               | `ADD CONSTRAINT ... NOT VALID` then `VALIDATE`   | online (8.0+ subject to conditions) | inline (PG10+) |
| Add foreign key                            | `ADD CONSTRAINT ... NOT VALID` then `VALIDATE`   | varies               | inline               |
| Rebuild table to reclaim bloat             | `pg_repack`                                       | pt-osc / gh-ost      | DBMS_REDEFINITION    |

## Files

```
src/main/java/com/claude/dbpoc/m19/
├── Application.java
├── service/
│   └── OnlineDdlService.java       # seed, addColumnSafe, addColumnUnsafe, createIndexConcurrently
└── web/
    └── OnlineDdlController.java    # /ddl/{seed,add-column-safe,add-column-unsafe,...}
```

## Related modules

- **[m11 - locking](../11-db-locking/)** — primer on lock modes.
- **[m14 - long-transaction](../14-db-long-transaction/)** — why
  chunked backfill matters.
- **[m18 - zero-downtime-migration](../18-db-zero-downtime-migration/)**
  — the expand/contract pattern that wraps these DDL changes.
- **[m20 - migration-rollback](../20-db-migration-rollback/)** — how
  to manage the SQL files (Flyway/Liquibase) around all this.
