package com.claude.dbpoc.m22.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Four partitioning demos, each self-contained:
 *
 *   1. RANGE — events by month. The default for time-series.
 *      Partition pruning lets a "last 7 days" query scan only the
 *      current month's partition instead of years of history.
 *
 *   2. LIST  — events by region. Use when the partition key has a
 *      small fixed set of values and queries usually filter by one.
 *
 *   3. HASH  — events by user_id. Use when there is NO natural range
 *      or list to partition by, but you still want to spread writes
 *      and parallelize scans across many tables. Lookup-by-user-id
 *      hits one partition; "all events" hits all of them.
 *
 *   4. Sliding window — the operational pattern that makes RANGE
 *      partitioning worth the complexity: dropping last year's data
 *      is now `DETACH PARTITION` + `DROP TABLE`, not a 4-hour DELETE
 *      that bloats the table and the WAL.
 *
 * Every demo dumps the EXPLAIN ANALYZE plan so the "Scanning 1 of N
 * partitions" line is right there in the response.
 */
@Service
public class PartitionService {

    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcTemplate jdbc;

    public PartitionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // =====================================================================
    // 1. RANGE partitioning — events by created_at month.
    // =====================================================================

    /**
     * Build a RANGE-partitioned events table covering the current month
     * and the previous 11 months. Seed `rowsPerMonth` rows into each.
     *
     * Key gotchas:
     *   - The PRIMARY KEY must include the partition key. Postgres
     *     can't enforce uniqueness across partitions otherwise.
     *   - The parent table holds no rows; all data lives in the child
     *     partitions.
     *   - We also create a DEFAULT partition as a safety net for rows
     *     that don't match any explicit range. In production you'd
     *     usually want INSERTS to fail rather than silently land in
     *     DEFAULT — drop it if so.
     */
    @Transactional
    public Map<String, Object> seedRange(int rowsPerMonth) {
        jdbc.execute("drop table if exists events_range cascade");
        jdbc.execute(
            "create table events_range (" +
            "  id          bigserial, " +
            "  created_at  timestamptz not null, " +
            "  user_id     bigint not null, " +
            "  payload     text, " +
            "  primary key (id, created_at)" +     // PK MUST include partition key
            ") partition by range (created_at)");

        // 12 monthly partitions: this month + previous 11.
        YearMonth thisMonth = YearMonth.now();
        for (int i = 11; i >= 0; i--) {
            YearMonth ym = thisMonth.minusMonths(i);
            String partName = "events_range_" + ym.format(YYYYMM);
            LocalDate from = ym.atDay(1);
            LocalDate to   = ym.plusMonths(1).atDay(1);
            jdbc.execute(
                "create table " + partName + " partition of events_range " +
                "for values from ('" + from + "') to ('" + to + "')");
        }
        // Default partition — catches anything outside the explicit ranges.
        jdbc.execute("create table events_range_default partition of events_range default");

        // Seed: rowsPerMonth into each of the 12 partitions, with a
        // timestamp spread across the month so range scans look realistic.
        for (int i = 11; i >= 0; i--) {
            YearMonth ym = thisMonth.minusMonths(i);
            LocalDate from = ym.atDay(1);
            int days = ym.lengthOfMonth();
            jdbc.update(
                "insert into events_range(created_at, user_id, payload) " +
                "select " +
                "  ?::timestamptz + (random() * interval '" + days + " days'), " +
                "  (random() * 100000)::bigint, " +
                "  'evt-' || g " +
                "from generate_series(1, ?) g",
                from.toString(), rowsPerMonth);
        }
        jdbc.execute("analyze events_range");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", "events_range");
        out.put("strategy", "RANGE BY (created_at) — monthly partitions");
        out.put("partitionsCreated", 13);
        out.put("rowsPerMonth", rowsPerMonth);
        out.put("totalRows", 12L * rowsPerMonth);
        out.put("partitionSizes", partitionSizes("events_range"));
        out.put("note",
            "PK is (id, created_at) — the partition key MUST be in every " +
            "unique constraint. A plain `primary key (id)` is rejected by Postgres on a " +
            "partitioned table.");
        return out;
    }

    /**
     * The point of RANGE partitioning: pruning.
     *
     * The query filters on the partition key (`created_at`), so the
     * planner walks only the matching partition(s). With 12 monthly
     * partitions of 100k rows each (1.2M total), a "last 7 days" query
     * touches ~1 partition instead of scanning 1.2M rows.
     *
     * In EXPLAIN you see:
     *   - The Append node lists ONLY the partition(s) the planner kept.
     *   - "Subplans Removed: N" — partitions pruned at planning time.
     */
    public Map<String, Object> rangePrune() {
        String sql =
            "select count(*), max(created_at) " +
            "from events_range " +
            "where created_at >= now() - interval '7 days'";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", sql);
        out.put("plan", explainAnalyze(sql));
        out.put("pruningTakeaway",
            "Look at the Append node — only the partition for THIS month appears. " +
            "All other partitions are pruned at plan time. Scaling to years of data " +
            "wouldn't change the runtime of this query — that's the win.");
        return out;
    }

    /**
     * Counter-example: a query that does NOT filter on the partition
     * key has to scan every partition. The plan shows an Append over all
     * children. Partitioning didn't help here — and the per-partition
     * overhead (planning cost + many small scans) can make it slightly
     * WORSE than a single non-partitioned table.
     *
     * Rule: if your hot queries don't filter by the partition key, you
     * picked the wrong key.
     */
    public Map<String, Object> rangeNoPrune() {
        String sql = "select count(*) from events_range where user_id = 42";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", sql);
        out.put("plan", explainAnalyze(sql));
        out.put("warning",
            "No filter on the partition key (created_at). Every partition is scanned. " +
            "If this is your hot query, partitioning by created_at was the wrong " +
            "choice — consider HASH BY user_id instead.");
        return out;
    }

    // =====================================================================
    // 2. LIST partitioning — events by region.
    // =====================================================================

    /**
     * Small fixed set of partition values. Each region gets its own
     * partition; queries that filter by region touch one partition.
     *
     * Use when:
     *   - the partition column has a small enumeration of values
     *   - queries almost always include `where region = ?`
     *   - you want region-level operations (drop a region, archive a
     *     region, move a region to its own tablespace)
     */
    @Transactional
    public Map<String, Object> seedList(int rowsPerRegion) {
        jdbc.execute("drop table if exists events_list cascade");
        jdbc.execute(
            "create table events_list (" +
            "  id      bigserial, " +
            "  region  text not null, " +
            "  user_id bigint not null, " +
            "  payload text, " +
            "  primary key (id, region)" +
            ") partition by list (region)");

        String[] regions = {"us-east", "us-west", "eu-west", "ap-south"};
        for (String r : regions) {
            String partName = "events_list_" + r.replace('-', '_');
            jdbc.execute(
                "create table " + partName + " partition of events_list " +
                "for values in ('" + r + "')");
        }
        jdbc.execute("create table events_list_other partition of events_list default");

        for (String r : regions) {
            jdbc.update(
                "insert into events_list(region, user_id, payload) " +
                "select ?, (random() * 100000)::bigint, 'evt-' || g " +
                "from generate_series(1, ?) g",
                r, rowsPerRegion);
        }
        jdbc.execute("analyze events_list");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", "events_list");
        out.put("strategy", "LIST BY (region) — one partition per known region");
        out.put("regions", regions);
        out.put("rowsPerRegion", rowsPerRegion);
        out.put("partitionSizes", partitionSizes("events_list"));
        return out;
    }

    public Map<String, Object> listPrune(String region) {
        String sql = "select count(*) from events_list where region = '" + region + "'";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", sql);
        out.put("plan", explainAnalyze(sql));
        out.put("note",
            "Plan touches only events_list_" + region.replace('-', '_') +
            ". The other three are pruned. " +
            "If you add a new region in prod, you MUST create its partition before " +
            "the first insert — otherwise the row lands in events_list_other (the " +
            "DEFAULT partition) and Postgres will SILENTLY accept it, which is " +
            "almost never what you want.");
        return out;
    }

    // =====================================================================
    // 3. HASH partitioning — events by user_id.
    // =====================================================================

    /**
     * No natural range or list, but high cardinality. Hash spreads the
     * rows evenly across N partitions. Lookups by user_id (the hash
     * column) hit one partition; scans without a user_id predicate hit
     * all of them.
     *
     * Use when:
     *   - you want write parallelism (one partition per CPU on heavy
     *     bulk loads)
     *   - you want predictable per-partition size
     *   - your hot query path is "give me the events for THIS user"
     *
     * Don't use when:
     *   - you also want time-range scans (you can't easily do both
     *     without sub-partitioning)
     *   - you ever need to "drop old data" — hash partitions can't be
     *     time-aligned for cheap drops
     */
    @Transactional
    public Map<String, Object> seedHash(int rowsTotal) {
        jdbc.execute("drop table if exists events_hash cascade");
        jdbc.execute(
            "create table events_hash (" +
            "  id      bigserial, " +
            "  user_id bigint not null, " +
            "  payload text, " +
            "  primary key (id, user_id)" +
            ") partition by hash (user_id)");

        int modulus = 8;
        for (int i = 0; i < modulus; i++) {
            jdbc.execute(
                "create table events_hash_p" + i + " partition of events_hash " +
                "for values with (modulus " + modulus + ", remainder " + i + ")");
        }

        jdbc.update(
            "insert into events_hash(user_id, payload) " +
            "select (random() * 100000)::bigint, 'evt-' || g " +
            "from generate_series(1, ?) g", rowsTotal);
        jdbc.execute("analyze events_hash");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", "events_hash");
        out.put("strategy", "HASH BY (user_id) MODULUS 8");
        out.put("partitions", modulus);
        out.put("rowsTotal", rowsTotal);
        out.put("partitionSizes", partitionSizes("events_hash"));
        out.put("note",
            "Postgres picks the partition via hashint8(user_id) % 8. Distribution " +
            "is approximately even but never exactly — depends on user_id values " +
            "and the hash function. Expect ±5% skew at scale.");
        return out;
    }

    public Map<String, Object> hashPrune(long userId) {
        String sql = "select count(*) from events_hash where user_id = " + userId;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", sql);
        out.put("plan", explainAnalyze(sql));
        out.put("note",
            "Equality on the hash column → exactly one partition scanned. " +
            "Range queries on user_id (user_id BETWEEN x AND y) CAN'T prune — " +
            "hash partitioning is for equality lookups only.");
        return out;
    }

    // =====================================================================
    // 4. Sliding window — DETACH old, ATTACH new. The reason RANGE
    // partitioning is worth the operational overhead.
    // =====================================================================

    /**
     * "Drop everything older than 6 months" without a giant DELETE.
     *
     * On a non-partitioned table, you'd run
     *     DELETE FROM events WHERE created_at < now() - interval '6 months';
     * which on a billion rows is hours of WAL, table bloat, and a vacuum
     * that has to catch up. On a partitioned table:
     *
     *     ALTER TABLE events_range DETACH PARTITION events_range_201501 CONCURRENTLY;
     *     DROP TABLE events_range_201501;
     *
     * Sub-second metadata operation. No bloat. No vacuum. The disk space
     * is returned immediately because the partition is a separate file.
     *
     * Pair with an "attach next month" step at the start of every month
     * and you have a sliding window: data older than N months is dropped,
     * the most recent month is freshly allocated, partition count stays
     * constant.
     */
    @Transactional
    public Map<String, Object> slideWindow(int dropMonthsOlderThan, boolean attachNext) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy",
            "Drop partitions older than " + dropMonthsOlderThan + " months, " +
            "attach next month's partition: " + attachNext);

        YearMonth cutoff = YearMonth.now().minusMonths(dropMonthsOlderThan);
        // Find existing partitions older than cutoff.
        List<String> oldParts = jdbc.queryForList(
            "select child.relname from pg_inherits " +
            "join pg_class parent on parent.oid = pg_inherits.inhparent " +
            "join pg_class child  on child.oid  = pg_inherits.inhrelid " +
            "where parent.relname = 'events_range' " +
            "  and child.relname like 'events_range_2%' " +     // skip _default
            "  and child.relname < ?",
            String.class,
            "events_range_" + cutoff.format(YYYYMM));

        for (String p : oldParts) {
            // DETACH PARTITION is fast (catalog-only) — switches the
            // child to a plain standalone table. We then DROP it for
            // good. Use DETACH CONCURRENTLY in production if writes are
            // hitting the parent at the same time.
            jdbc.execute("alter table events_range detach partition " + p);
            jdbc.execute("drop table " + p);
        }
        out.put("droppedPartitions", oldParts);

        if (attachNext) {
            // Build next month's partition off-band, then ATTACH. This
            // pattern lets you populate / index the partition in
            // isolation before it becomes visible to queries.
            YearMonth nextMonth = YearMonth.now().plusMonths(1);
            String nextName = "events_range_" + nextMonth.format(YYYYMM);
            LocalDate from = nextMonth.atDay(1);
            LocalDate to   = nextMonth.plusMonths(1).atDay(1);

            // Skip if already attached
            Integer exists = jdbc.queryForObject(
                "select count(*) from pg_class where relname = ?",
                Integer.class, nextName);
            if (exists == null || exists == 0) {
                jdbc.execute(
                    "create table " + nextName + " (like events_range including all)");
                jdbc.execute(
                    "alter table events_range attach partition " + nextName +
                    " for values from ('" + from + "') to ('" + to + "')");
                out.put("attachedPartition", nextName);
            } else {
                out.put("attachedPartition", "already exists: " + nextName);
            }
        }

        out.put("partitionsAfter", partitionSizes("events_range"));
        out.put("note",
            "DETACH + DROP is metadata-only — no row scan, no WAL flood. " +
            "Compare to DELETE on a non-partitioned table where you'd scan and " +
            "log every removed row, then vacuum to reclaim space. This is the " +
            "single biggest operational win of partitioning.");
        return out;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** EXPLAIN ANALYZE the query and return a list of plan lines. */
    private List<String> explainAnalyze(String sql) {
        return jdbc.queryForList("explain (analyze, buffers, format text) " + sql, String.class);
    }

    /** List partitions of a partitioned table with their sizes. */
    private List<Map<String, Object>> partitionSizes(String parent) {
        return jdbc.queryForList(
            "select child.relname as partition, " +
            "       pg_size_pretty(pg_total_relation_size(child.oid)) as size, " +
            "       (select reltuples::bigint from pg_class where oid = child.oid) as approx_rows " +
            "from pg_inherits " +
            "join pg_class parent on parent.oid = pg_inherits.inhparent " +
            "join pg_class child  on child.oid  = pg_inherits.inhrelid " +
            "where parent.relname = ? " +
            "order by child.relname",
            parent);
    }
}
