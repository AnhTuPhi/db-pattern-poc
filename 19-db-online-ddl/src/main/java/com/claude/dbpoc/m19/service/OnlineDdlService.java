package com.claude.dbpoc.m19.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Five things this module shows:
 *
 *   1. seed             — set up a "users" table with ~50k rows to make
 *                         lock effects visible.
 *   2. measureLock      — run a DDL statement and report the lock level
 *                         it took, by inspecting pg_locks during the call
 *                         and pg_class lock_type after.
 *   3. addColumnSafe    — ALTER ADD COLUMN with no default — fast,
 *                         metadata-only since PG11. Demonstrates the safe
 *                         pattern.
 *   4. addColumnUnsafe  — ALTER ADD COLUMN with a VOLATILE default —
 *                         forces a full table rewrite. Shows the trap.
 *   5. createIndexConcurrently — CREATE INDEX CONCURRENTLY: builds the
 *                         index without blocking writes. The "right" way
 *                         to add an index on a hot table.
 */
@Service
public class OnlineDdlService {

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public OnlineDdlService(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @Transactional
    public Map<String, Object> seed(int rows) {
        jdbc.execute("drop table if exists users cascade");
        jdbc.execute(
            "create table users (" +
            "  id bigserial primary key, " +
            "  email text not null, " +
            "  full_name text not null " +
            ")");
        jdbc.update("insert into users(email, full_name) " +
                    "select 'u' || g || '@example.com', 'User ' || g " +
                    "from generate_series(1, ?) g", rows);
        jdbc.execute("analyze users");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seeded", rows);
        out.put("sizeBefore", tableSize());
        return out;
    }

    /**
     * Safe ALTER ADD COLUMN — no default, or a CONSTANT default (PG11+).
     *
     * Since PG11, `ADD COLUMN ... DEFAULT <constant>` writes the default
     * to the catalog (pg_attribute.atthasmissing + attmissingval) and
     * does NOT rewrite the table. Existing rows return the default on
     * read. New rows are written normally. Result: O(1) metadata
     * operation, regardless of table size.
     *
     * Variants that are STILL safe:
     *   ADD COLUMN x int                       -- nullable, no default
     *   ADD COLUMN x int DEFAULT 0             -- constant default (PG11+)
     *   ADD COLUMN x int NOT NULL DEFAULT 0    -- NOT NULL + constant default (PG11+)
     *   ADD COLUMN x text DEFAULT 'a'          -- constant text default (PG11+)
     */
    public Map<String, Object> addColumnSafe() {
        long t0 = System.nanoTime();
        jdbc.execute("alter table users add column status text not null default 'ACTIVE'");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statement", "alter table users add column status text not null default 'ACTIVE'");
        out.put("elapsedMs", elapsedMs);
        out.put("note",
            "PG11+ metadata-only operation. No table rewrite. Even on a 1B-row table this is " +
            "nearly instant. The default lives in pg_attribute.attmissingval and is " +
            "synthesized at read time.");
        out.put("sizeAfter", tableSize());
        return out;
    }

    /**
     * Unsafe variant — VOLATILE default forces a full rewrite.
     *
     * Defaults that are VOLATILE (random(), now(), nextval('seq')) can't
     * be stored as a single attmissingval — each row needs a unique
     * evaluation. Postgres falls back to the pre-PG11 behavior: rewrite
     * the entire table, evaluating the default per row.
     *
     * On a 10M-row table this is many minutes of AccessExclusiveLock,
     * which means NO reads or writes during that window. Outage.
     */
    public Map<String, Object> addColumnUnsafe() {
        long t0 = System.nanoTime();
        jdbc.execute("alter table users add column token uuid not null default gen_random_uuid()");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statement", "alter table users add column token uuid not null default gen_random_uuid()");
        out.put("elapsedMs", elapsedMs);
        out.put("note",
            "VOLATILE default (gen_random_uuid is volatile) forces a full table rewrite. " +
            "On this small table it took " + elapsedMs + "ms but takes AccessExclusiveLock " +
            "the whole time — NO reads, NO writes. Multiply by your real table size for the " +
            "production impact.");
        out.put("sizeAfter", tableSize());
        out.put("saferAlternative",
            "1. ALTER TABLE users ADD COLUMN token uuid;   -- nullable, fast (metadata only)\n" +
            "2. UPDATE users SET token = gen_random_uuid() WHERE token IS NULL;   -- chunked\n" +
            "3. ALTER TABLE users ALTER COLUMN token SET NOT NULL;   -- fast since PG12 if no nulls\n" +
            "4. ALTER TABLE users ALTER COLUMN token SET DEFAULT gen_random_uuid();");
        return out;
    }

    /**
     * CREATE INDEX CONCURRENTLY — the right way to add an index on a hot
     * table. Takes ShareUpdateExclusiveLock instead of ShareLock, which
     * means concurrent writes are NOT blocked. Trade-off: takes longer
     * (two scans), can't be run inside a tx, and on failure leaves an
     * INVALID index you have to drop and recreate.
     *
     * NOTE: This method runs in its own connection-with-autocommit since
     * CREATE INDEX CONCURRENTLY cannot run inside a transaction block.
     */
    public Map<String, Object> createIndexConcurrently() {
        long t0 = System.nanoTime();
        // CREATE INDEX CONCURRENTLY cannot run inside a transaction block.
        // Borrow a raw connection, force autocommit, run the DDL.
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("create index concurrently if not exists users_email_idx on users(email)");
            }
        } catch (SQLException e) {
            throw new RuntimeException("CREATE INDEX CONCURRENTLY failed: " + e.getMessage(), e);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statement", "create index concurrently users_email_idx on users(email)");
        out.put("elapsedMs", elapsedMs);
        out.put("note",
            "ShareUpdateExclusiveLock — writers NOT blocked. Two passes over the table, so " +
            "slower than CREATE INDEX, but the table stays online. On failure (duplicate " +
            "value, OOM) the index is left INVALID — check with `select indisvalid from " +
            "pg_index where indexrelid = 'users_email_idx'::regclass;` and DROP + retry.");
        return out;
    }

    /**
     * Snapshot of locks Postgres is currently holding on the users table.
     * Useful right after a DDL to see the lock mode it acquired.
     */
    public Map<String, Object> currentLocks() {
        List<Map<String, Object>> rows = jdbc.query(
            "select " +
            "  pid, locktype, mode, granted, " +
            "  case when classid <> 0 then 'system' else relation::regclass::text end as relation, " +
            "  pg_blocking_pids(pid)::text as blocked_by " +
            "from pg_locks " +
            "where pid <> pg_backend_pid()",
            (rs, n) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pid", rs.getInt(1));
                m.put("locktype", rs.getString(2));
                m.put("mode", rs.getString(3));
                m.put("granted", rs.getBoolean(4));
                m.put("relation", rs.getString(5));
                m.put("blockedBy", rs.getString(6));
                return m;
            });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("locks", rows);
        return out;
    }

    /** Show the schema as it currently is — for verifying the demo state. */
    public Map<String, Object> describe() {
        List<Map<String, Object>> cols = jdbc.queryForList(
            "select column_name, data_type, is_nullable, column_default " +
            "from information_schema.columns " +
            "where table_schema = current_schema() and table_name = 'users' " +
            "order by ordinal_position");
        List<Map<String, Object>> idx = jdbc.queryForList(
            "select indexname, indexdef from pg_indexes " +
            "where schemaname = current_schema() and tablename = 'users'");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", cols);
        out.put("indexes", idx);
        out.put("size", tableSize());
        return out;
    }

    private String tableSize() {
        return jdbc.queryForObject(
            "select pg_size_pretty(pg_total_relation_size('users'::regclass))",
            String.class);
    }
}
