package com.claude.dbpoc.m04;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three lessons.
 *
 *   1. /demo/stale-stats        — what fresh ANALYZE buys you
 *   2. /demo/correlation        — why the planner gets correlated predicates wrong
 *      /demo/correlation/fix    — CREATE STATISTICS to teach it the truth
 *   3. /demo/skew               — high-cardinality vs rare-value: same query, different plan
 *
 * Each endpoint returns a JSON shape that puts {estimatedRows, actualRows,
 * ratioOff, planChosen} at the top, so the divergence (or lack of it) is the
 * first thing the reader sees.
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private final JdbcTemplate jdbc;
    private final EstimateExtractor estimates;

    public DemoController(JdbcTemplate jdbc, EstimateExtractor estimates) {
        this.jdbc = jdbc;
        this.estimates = estimates;
    }

    // ------------------------------------------------------------------------
    // 1. STALE STATISTICS
    // ------------------------------------------------------------------------

    /**
     * Show what the planner currently thinks about a common predicate. The
     * "before" run is whatever pg_stats says right now — if you've just done a
     * /seed/initial WITHOUT /seed/analyze, the planner is using default n_distinct
     * and no MCV list, and its estimate will be wildly off.
     *
     * After this endpoint runs, also try /seed/analyze then call this again —
     * the ratio should snap back to ~1.0.
     */
    @GetMapping("/stale-stats")
    public Map<String, Object> staleStats() {
        // A typical query: count US orders. With ~90% of rows being US this is the
        // canonical "Seq Scan is right" case — but only if stats know that.
        String sql = "SELECT count(*) FROM orders WHERE country_code = 'US'";
        EstimateExtractor.PlanReport report = estimates.explain(sql);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", sql);
        out.put("estimatedRows", report.rootEstimatedRows());
        out.put("actualRows", report.rootActualRows());
        out.put("ratioOff", report.rootRatioOff());
        out.put("planChosen", planLabel(report));
        out.put("planningMs", report.planningMs());
        out.put("executionMs", report.executionMs());
        out.put("nodes", report.nodes());
        out.put("statsFreshness", statsFreshness());
        out.put("hint", "If estimatedRows is far from actualRows, run POST /seed/analyze and try again.");
        return out;
    }

    // ------------------------------------------------------------------------
    // 2. CORRELATED COLUMNS
    // ------------------------------------------------------------------------

    /**
     * The compound predicate. country_code='US' selects ~90% of the table,
     * region='CALIFORNIA' selects ~20% of US rows (since US has 5 regions).
     * In reality, 'CALIFORNIA' rows are a strict subset of 'US' rows — the two
     * predicates carry the same information.
     *
     * Postgres without extended stats assumes independence:
     *      sel(country='US')   ≈ 0.90
     *      sel(region='CA')    ≈ 0.18           (0.90 * (1/5))
     *      sel(both)           ≈ 0.90 * 0.18 = 0.162  → guesses ~162k
     *
     * Reality: the combined predicate matches every 'CALIFORNIA' row, so
     * count ≈ 0.18 * total ≈ 180k. The planner is off by a factor of ~1.1 here
     * — but in production with sparser correlations (e.g. customer_id and
     * customer_segment) the same independence assumption misses by 10× to 100×.
     */
    @GetMapping("/correlation")
    public Map<String, Object> correlation(@RequestParam(defaultValue = "US") String country,
                                           @RequestParam(defaultValue = "CALIFORNIA") String region) {
        String sql = "SELECT count(*) FROM orders WHERE country_code = ? AND region = ?";
        EstimateExtractor.PlanReport report = estimates.explain(sql, country, region);

        boolean extendedStatsPresent = hasExtendedStats();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", sql);
        out.put("params", Map.of("country", country, "region", region));
        out.put("estimatedRows", report.rootEstimatedRows());
        out.put("actualRows", report.rootActualRows());
        out.put("ratioOff", report.rootRatioOff());
        out.put("planChosen", planLabel(report));
        out.put("executionMs", report.executionMs());
        out.put("extendedStatsPresent", extendedStatsPresent);
        out.put("nodes", report.nodes());
        if (!extendedStatsPresent) {
            out.put("hint", "No extended stats. Postgres multiplied per-column selectivities (assumes independence). Try POST /demo/correlation/fix.");
        } else {
            out.put("hint", "Extended stats are present. estimatedRows should be close to actualRows.");
        }
        return out;
    }

    /**
     * The fix for correlated columns: extended statistics. CREATE STATISTICS
     * teaches Postgres about the dependency (knowing country_code lets it predict
     * region, and vice versa) and the joint distinct count. Then re-ANALYZE so
     * the planner actually has the new numbers.
     *
     * After this runs, /demo/correlation should show ratioOff ≈ 1.0.
     */
    @PostMapping("/correlation/fix")
    public Map<String, Object> correlationFix(@RequestParam(defaultValue = "US") String country,
                                              @RequestParam(defaultValue = "CALIFORNIA") String region) {
        // 'dependencies' is the kind that fixes selectivity multiplication;
        // 'ndistinct' helps GROUP BY estimates. 'mcv' (Postgres 12+) builds a
        // multivariate most-common-values list — we include all three for the demo.
        jdbc.execute("DROP STATISTICS IF EXISTS orders_country_region");
        jdbc.execute("CREATE STATISTICS orders_country_region (dependencies, ndistinct, mcv) ON country_code, region FROM orders");
        jdbc.execute("ANALYZE orders");

        String sql = "SELECT count(*) FROM orders WHERE country_code = ? AND region = ?";
        EstimateExtractor.PlanReport after = estimates.explain(sql, country, region);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actionTaken", "CREATE STATISTICS orders_country_region (dependencies, ndistinct, mcv) ON country_code, region; ANALYZE orders");
        out.put("query", sql);
        out.put("params", Map.of("country", country, "region", region));
        out.put("estimatedRows", after.rootEstimatedRows());
        out.put("actualRows", after.rootActualRows());
        out.put("ratioOff", after.rootRatioOff());
        out.put("planChosen", planLabel(after));
        out.put("executionMs", after.executionMs());
        out.put("nodes", after.nodes());
        return out;
    }

    // ------------------------------------------------------------------------
    // 3. SKEW
    // ------------------------------------------------------------------------

    /**
     * Same query, different parameter, completely different correct plan:
     *
     *   country='US' → ~90% of table → Seq Scan is cheapest
     *   country='AQ' → ~10 rows out of 1M → Index Scan is cheapest
     *
     * The planner gets this RIGHT when pg_stats has both:
     *   - The MCV list showing 'US' at ~0.90 frequency.
     *   - A non-zero estimate for 'AQ' from the histogram remainder.
     *
     * If stats are stale (or default_statistics_target is too low to capture
     * 'AQ' at all), the planner can pick the wrong plan for the rare value.
     */
    @GetMapping("/skew")
    public Map<String, Object> skew(@RequestParam(defaultValue = "US") String country) {
        String sql = "SELECT count(*) FROM orders WHERE country_code = ?";
        EstimateExtractor.PlanReport report = estimates.explain(sql, country);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", sql);
        out.put("country", country);
        out.put("estimatedRows", report.rootEstimatedRows());
        out.put("actualRows", report.rootActualRows());
        out.put("ratioOff", report.rootRatioOff());
        out.put("planChosen", planLabel(report));
        out.put("executionMs", report.executionMs());
        out.put("nodes", report.nodes());
        out.put("expectation",
                "US → Seq Scan (90% of table). AQ → Index Scan (handful of rows). " +
                "If you see Seq Scan for AQ, stats are stale or default_statistics_target is too low.");
        return out;
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** "Seq Scan" or "Index Scan using idx_orders_country" — whichever the planner picked. */
    private static String planLabel(EstimateExtractor.PlanReport r) {
        // Walk into the first scan node — the root might be an Aggregate.
        for (EstimateExtractor.NodeEstimate n : r.nodes()) {
            if (n.nodeType().endsWith("Scan")) {
                return n.indexName().isEmpty() ? n.nodeType() : n.nodeType() + " using " + n.indexName();
            }
        }
        return r.rootNodeType();
    }

    /** When was the table last analyzed, and how many rows are stale since then? */
    private Map<String, Object> statsFreshness() {
        return jdbc.queryForMap("""
                SELECT
                    last_analyze,
                    last_autoanalyze,
                    n_live_tup,
                    n_mod_since_analyze
                FROM pg_stat_user_tables
                WHERE relname = 'orders'
                """);
    }

    private boolean hasExtendedStats() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*)::int FROM pg_statistic_ext WHERE stxname = 'orders_country_region'",
                Integer.class);
        return n != null && n > 0;
    }
}
