package com.claude.dbpoc.m03;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Synthetic production-shaped workload.
 *
 * The idea: run a *fixed* set of ~20 distinct query texts so that
 * pg_stat_statements groups them tightly (same query text -> same row in the
 * view; different `?` parameters don't matter, but different SQL texts do).
 *
 * Several of these queries are deliberately *bad* — they're the bait the
 * SRE will find when reading /top later. Every bad one is labelled in the
 * code comment with the smell it represents.
 *
 * Concurrency: 8 worker threads. Realistic-ish for a single web pod;
 * enough to surface lock/IO contention without melting a dev laptop.
 */
@RestController
public class WorkloadController {

    private static final int THREADS = 8;

    /**
     * Fixed list of ~50 parameter sets so the SAME query text is executed
     * many times. pg_stat_statements normalises `?` literals, so the goal
     * here is simply: produce repeatable load with predictable patterns.
     */
    private static final long[] ACCOUNT_IDS = new long[50];
    static {
        for (int i = 0; i < ACCOUNT_IDS.length; i++) {
            // Spread across the 100k row space.
            ACCOUNT_IDS[i] = 1L + i * 2_000L;
        }
    }
    private static final String[] ENTITY_TYPES =
            {"account", "transaction", "user", "session"};

    private final JdbcTemplate pg;

    public WorkloadController(JdbcTemplate pgJdbc) {
        this.pg = pgJdbc;
    }

    @PostMapping("/workload/start")
    public Map<String, Object> start(@RequestParam(defaultValue = "30") int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1_000L;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicLong queriesRun = new AtomicLong();

        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (System.currentTimeMillis() < deadline) {
                    try {
                        runOne(rnd);
                        queriesRun.incrementAndGet();
                    } catch (Exception e) {
                        // Don't kill the worker — just count and move on.
                        // In a real prod sim you'd record the failure too.
                    }
                }
            });
        }

        pool.shutdown();
        try {
            pool.awaitTermination(seconds + 5L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "done");
        out.put("seconds", seconds);
        out.put("threads", THREADS);
        out.put("queries_run", queriesRun.get());
        return out;
    }

    /**
     * One iteration picks a query at random from the catalogue. We use a
     * weighted distribution: each "bad" query is hit a bit more often so
     * it dominates pg_stat_statements quickly and the workshop is fast.
     */
    private void runOne(ThreadLocalRandom rnd) {
        int dice = rnd.nextInt(100);

        // ============== BAD QUERIES (deliberate; ~55% of traffic) ==============

        if (dice < 20) {
            // BAD #1: FK without supporting index. Sequential scan of 5M rows.
            // Symptom: extremely high mean_exec_time and shared_blks_read.
            long acct = ACCOUNT_IDS[rnd.nextInt(ACCOUNT_IDS.length)];
            pg.queryForList("SELECT * FROM transactions WHERE account_id = ?", acct);
            return;
        }
        if (dice < 35) {
            // BAD #2: missing compound index on (entity_type, entity_id).
            String type = ENTITY_TYPES[rnd.nextInt(ENTITY_TYPES.length)];
            long entId = ACCOUNT_IDS[rnd.nextInt(ACCOUNT_IDS.length)];
            pg.queryForList(
                    "SELECT * FROM audit_log WHERE entity_type = ? AND entity_id = ?",
                    type, entId);
            return;
        }
        if (dice < 45) {
            // BAD #3: function on column. Even if created_at were indexed,
            // EXTRACT(MONTH FROM created_at) is not sargable — index unused.
            pg.queryForList(
                    "SELECT * FROM transactions WHERE EXTRACT(MONTH FROM created_at) = 3");
            return;
        }
        if (dice < 55) {
            // BAD #4: implicit type cast. account_id is BIGINT; casting it
            // to text forces a full scan even if the FK index existed.
            long acct = ACCOUNT_IDS[rnd.nextInt(ACCOUNT_IDS.length)];
            pg.queryForList(
                    "SELECT * FROM transactions WHERE account_id::text = ?",
                    String.valueOf(acct));
            return;
        }

        // ============== GOOD QUERIES (~45% of traffic) =========================

        if (dice < 70) {
            // GOOD: PK lookup. Sub-ms, dominates `calls` but barely shows
            // up in `total_exec_time`. Useful contrast in /top output.
            long acct = ACCOUNT_IDS[rnd.nextInt(ACCOUNT_IDS.length)];
            pg.queryForList("SELECT * FROM accounts WHERE id = ?", acct);
            return;
        }
        if (dice < 80) {
            // GOOD: count by type, bounded by date. The planner can scan
            // a reasonable slice; this is the "noise" in /top results.
            String type = ENTITY_TYPES[rnd.nextInt(ENTITY_TYPES.length)];
            pg.queryForList(
                    "SELECT count(*) FROM audit_log WHERE action = ? AND ts > now() - interval '7 days'",
                    type);
            return;
        }
        if (dice < 88) {
            // GOOD: small range scan on PK.
            long lo = ACCOUNT_IDS[rnd.nextInt(ACCOUNT_IDS.length)];
            pg.queryForList(
                    "SELECT id, owner_name FROM accounts WHERE id BETWEEN ? AND ?",
                    lo, lo + 100);
            return;
        }
        if (dice < 94) {
            // GOOD: aggregation. Slow-ish but expected; gives /top some
            // "legitimately heavy" queries to show alongside the smells.
            pg.queryForList(
                    "SELECT type, count(*), sum(amount) FROM transactions WHERE amount > ? GROUP BY type",
                    rnd.nextInt(900));
            return;
        }

        // Fallback: tiny meta query, like a health check.
        pg.queryForList("SELECT now()");
    }

    /**
     * Convenience: simple GET so curl-by-default usage is easy. Returns
     * the same payload as /workload/start.
     */
    @PostMapping("/workload/start/{seconds}")
    public Map<String, Object> startPath(@org.springframework.web.bind.annotation.PathVariable int seconds) {
        return start(seconds);
    }

    // Held for completeness if a caller wants the list of bad-query labels
    // for documentation purposes.
    public List<String> badQueryCatalogue() {
        List<String> list = new ArrayList<>();
        list.add("BAD#1 missing FK index: SELECT * FROM transactions WHERE account_id = ?");
        list.add("BAD#2 missing compound index: SELECT * FROM audit_log WHERE entity_type = ? AND entity_id = ?");
        list.add("BAD#3 function-on-column: SELECT * FROM transactions WHERE EXTRACT(MONTH FROM created_at) = 3");
        list.add("BAD#4 implicit cast: SELECT * FROM transactions WHERE account_id::text = ?");
        return list;
    }
}
