package com.claude.dbpoc.m04;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads the orders table with a deliberately realistic distribution:
 *
 *   - country_code: ~90% 'US', ~5% 'DE', ~3% 'GB', ~1.5% 'FR', ~0.5% 'JP',
 *                   and ~0.001% 'AQ' (Antarctica — the planner's nightmare,
 *                   a value so rare it barely shows up in any histogram).
 *   - region: tightly CORRELATED with country_code. 'CALIFORNIA' only occurs
 *             when country_code='US'; 'BAVARIA' only when country_code='DE';
 *             and so on. This is the setup for the extended-stats demo.
 *
 * Crucially, /seed/initial does NOT run ANALYZE. That mimics what happens in
 * production right after a bulk load: the data is there, but pg_stats hasn't
 * caught up yet, so the planner is operating on a phantom view of the table.
 */
@RestController
@RequestMapping("/seed")
public class SeedController {

    private final JdbcTemplate jdbc;

    public SeedController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Wipe + seed N rows with the standard skewed/correlated distribution.
     * Deliberately skips ANALYZE so /demo/stale-stats can show what happens
     * when the planner is operating on default (n_distinct=-1, no MCV) stats.
     */
    @PostMapping("/initial")
    public Map<String, Object> seedInitial(@RequestParam(defaultValue = "1000000") int rows) {
        // Truncate to start fresh — RESTART IDENTITY resets the BIGSERIAL.
        jdbc.execute("TRUNCATE TABLE orders RESTART IDENTITY");
        long inserted = bulkInsert(rows);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rowsRequested", rows);
        out.put("rowsInserted", inserted);
        out.put("analyzeRun", false);
        out.put("note", "ANALYZE deliberately NOT run — call /demo/stale-stats next to see the planner work with default estimates.");
        return out;
    }

    /**
     * Append rows on top of the existing table. Mimics a bulk batch load — the
     * point being that even AFTER an initial /analyze, a subsequent fat insert
     * leaves pg_stats stale. Call /demo/stale-stats afterwards to see it.
     */
    @PostMapping("/bulk-load")
    public Map<String, Object> bulkLoad(@RequestParam(defaultValue = "200000") int rows) {
        long before = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
        long inserted = bulkInsert(rows);
        long after = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rowsBefore", before);
        out.put("rowsInserted", inserted);
        out.put("rowsAfter", after);
        out.put("analyzeRun", false);
        out.put("note", "Bulk load done. pg_stat_user_tables.n_mod_since_analyze is now non-zero.");
        return out;
    }

    /**
     * Synchronous ANALYZE. Fast on a million rows; this is the cheap fix you
     * almost always want to try first.
     */
    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestParam(required = false) String column) {
        long start = System.nanoTime();
        if (column == null || column.isBlank()) {
            jdbc.execute("ANALYZE orders");
        } else {
            // ANALYZE on a single column — handy when only one column's stats are stale.
            jdbc.execute("ANALYZE orders (" + sanitizeColumn(column) + ")");
        }
        double ms = (System.nanoTime() - start) / 1_000_000.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ranAnalyzeOn", column == null || column.isBlank() ? "orders (all columns)" : "orders (" + column + ")");
        out.put("elapsedMs", Math.round(ms * 100.0) / 100.0);
        out.put("note", "pg_stats now reflects current data. Re-run the same demo endpoint to see the planner's new estimate.");
        return out;
    }

    // ------------------------------------------------------------------------
    // Internals — the actual row generation. Pure SQL so it runs at server-side
    // speed (a million rows in ~5-10s on a laptop, no JDBC round-trip per row).
    // ------------------------------------------------------------------------

    /**
     * Generates `rows` rows in a single INSERT ... SELECT against generate_series.
     * The CASE expressions encode the skewed-country + correlated-region story.
     */
    private long bulkInsert(int rows) {
        String sql = """
                INSERT INTO orders (country_code, region, customer_id, amount, created_at)
                SELECT
                    cc.country_code,
                    -- region is fully determined by country (perfect correlation, the worst case for the planner)
                    CASE cc.country_code
                        WHEN 'US' THEN (ARRAY['CALIFORNIA','TEXAS','NEW_YORK','FLORIDA','ILLINOIS'])[1 + (g % 5)]
                        WHEN 'DE' THEN (ARRAY['BAVARIA','BERLIN','HAMBURG'])[1 + (g % 3)]
                        WHEN 'GB' THEN (ARRAY['LONDON','SCOTLAND'])[1 + (g % 2)]
                        WHEN 'FR' THEN (ARRAY['ILE_DE_FRANCE','PROVENCE'])[1 + (g % 2)]
                        WHEN 'JP' THEN 'KANTO'
                        WHEN 'AQ' THEN 'MCMURDO'
                        ELSE 'UNKNOWN'
                    END                                                                    AS region,
                    (random() * 99999)::bigint                                             AS customer_id,
                    (random() * 1000)::numeric(12,2)                                       AS amount,
                    now() - (random() * interval '365 days')                               AS created_at
                FROM (
                    SELECT
                        g,
                        -- Skewed country mix: 90/5/3/1.5/0.499/0.001 (the 0.001 'AQ' is the rare-value demo)
                        CASE
                            WHEN r < 0.90000  THEN 'US'
                            WHEN r < 0.95000  THEN 'DE'
                            WHEN r < 0.98000  THEN 'GB'
                            WHEN r < 0.99500  THEN 'FR'
                            WHEN r < 0.99999  THEN 'JP'
                            ELSE                   'AQ'
                        END AS country_code
                    FROM (
                        SELECT g, random() AS r
                        FROM generate_series(1, ?) AS g
                    ) s
                ) cc
                """;
        return jdbc.update(sql, rows);
    }

    /** Belt-and-braces guard against SQL injection in ANALYZE col list. */
    private static String sanitizeColumn(String column) {
        if (!column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid column name: " + column);
        }
        return column;
    }
}
