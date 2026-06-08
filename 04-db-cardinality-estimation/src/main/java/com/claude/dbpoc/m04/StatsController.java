package com.claude.dbpoc.m04;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads/writes the raw planner inputs.
 *
 *   GET /stats/columns                 — what pg_stats actually contains for orders
 *   POST /stats/target?...             — bump per-column histogram resolution
 *
 * The "columns" endpoint is the one to bookmark: when a query is slow in
 * production and you suspect the planner has a bad picture, this is the table
 * that tells you what the picture actually IS.
 */
@RestController
@RequestMapping("/stats")
public class StatsController {

    private final JdbcTemplate jdbc;

    public StatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Show the per-column stats the planner is using right now:
     *   - most_common_vals / most_common_freqs : the MCV list (top N values + frequencies)
     *   - n_distinct                           : estimated number of distinct values
     *                                            (negative ⇒ fraction of n_live_tup,
     *                                             so -0.5 means "about half the rows are distinct")
     *   - histogram_bounds                     : the equi-depth histogram (one bucket per pct)
     *
     * If most_common_vals is empty for a column you'd expect to be skewed (like
     * country_code), the table hasn't been ANALYZEd since the data landed.
     */
    @GetMapping("/columns")
    public List<Map<String, Object>> columns() {
        return jdbc.queryForList("""
                SELECT
                    attname            AS column_name,
                    n_distinct,
                    most_common_vals,
                    most_common_freqs,
                    histogram_bounds,
                    correlation
                FROM pg_stats
                WHERE schemaname = current_schema()
                  AND tablename  = 'orders'
                ORDER BY attname
                """);
    }

    /**
     * Raise the per-column statistics target. The default is 100 buckets, which
     * is fine for most columns but too coarse for high-cardinality skewed ones
     * (or for the long tail of a country_code column where 'AQ' might fall off
     * the MCV list entirely).
     *
     * Note: SET STATISTICS only takes effect AFTER the next ANALYZE, which this
     * endpoint runs for you.
     */
    @PostMapping("/target")
    public Map<String, Object> setTarget(@RequestParam String column,
                                         @RequestParam(defaultValue = "1000") int target) {
        if (target < 1 || target > 10000) {
            throw new IllegalArgumentException("target must be between 1 and 10000");
        }
        String col = sanitizeColumn(column);

        jdbc.execute("ALTER TABLE orders ALTER COLUMN " + col + " SET STATISTICS " + target);
        long start = System.nanoTime();
        jdbc.execute("ANALYZE orders (" + col + ")");
        double ms = (System.nanoTime() - start) / 1_000_000.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("column", col);
        out.put("newTarget", target);
        out.put("analyzeElapsedMs", Math.round(ms * 100.0) / 100.0);
        out.put("note", "The MCV list now has up to " + target + " entries (was 100). Re-check GET /stats/columns and re-run the relevant /demo endpoint.");
        return out;
    }

    private static String sanitizeColumn(String column) {
        if (!column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid column name: " + column);
        }
        return column;
    }
}
