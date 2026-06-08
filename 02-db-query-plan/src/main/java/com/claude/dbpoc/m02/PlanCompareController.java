package com.claude.dbpoc.m02;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Force the planner's hand to demonstrate WHY it picked what it picked.
 *
 * Two tactics in this controller:
 *   1. Postgres "session knobs" — SET enable_seqscan = off forces the planner
 *      to assign a stupidly high cost to Seq Scan so it falls back to the
 *      next-best plan. This is a DIAGNOSTIC TOOL ONLY. Never ship it.
 *   2. Oracle optimizer hints — /*+ FULL *\/ vs /*+ INDEX *\/ in the SQL itself.
 *      Oracle gives you per-statement override syntax; Postgres does not.
 *
 * The teaching point: if disabling a plan makes the query slower, the planner
 * was right. If it makes it faster, your STATS are wrong. The fix is fresh
 * statistics, better column distributions, or extended stats — NOT shipping
 * `SET enable_seqscan = off` to production.
 */
@RestController
@RequestMapping("/compare")
public class PlanCompareController {

    private final JdbcTemplate pg;
    private final ExplainPg explainPg;
    private final ObjectProvider<JdbcTemplate> oracleJdbc;
    private final ObjectProvider<ExplainOracle> explainOracleProvider;

    public PlanCompareController(@Qualifier("pgJdbc") JdbcTemplate pg,
                                 ExplainPg explainPg,
                                 @Qualifier("oracleJdbc") ObjectProvider<JdbcTemplate> oracleJdbc,
                                 ObjectProvider<ExplainOracle> explainOracleProvider) {
        this.pg = pg;
        this.explainPg = explainPg;
        this.oracleJdbc = oracleJdbc;
        this.explainOracleProvider = explainOracleProvider;
    }

    // ----- Postgres: disable/enable seqscan, then re-EXPLAIN ---------------

    @PostMapping("/disable-seqscan")
    public Map<String, Object> disableSeqScan() {
        // Session-local. Hikari may hand this connection back to the pool with
        // the setting sticking — for a demo that's fine, but in production
        // code you'd use SET LOCAL inside a transaction.
        pg.execute("SET enable_seqscan = off");
        return runComparison("disabled");
    }

    @PostMapping("/enable-seqscan")
    public Map<String, Object> enableSeqScan() {
        pg.execute("SET enable_seqscan = on");
        return runComparison("re-enabled");
    }

    private Map<String, Object> runComparison(String state) {
        // Same predicate as /plans/seq-scan — the only thing we change is the knob.
        String sql = "SELECT * FROM orders WHERE status = 'PAID'";
        Map<String, Object> plan = explainPg.explain(sql);
        Map<String, Object> out = new LinkedHashMap<>(plan);
        out.put("enable_seqscan", state);
        out.put("lesson",
                "Compare the totalCost vs the /plans/seq-scan baseline. If the " +
                "forced plan is MORE expensive (it usually is here), the planner " +
                "was right to pick Seq Scan. Never ship `SET enable_seqscan = off` " +
                "— it's a diagnostic that tells you whether to chase stats or schema.");
        return out;
    }

    // ----- Oracle: optimizer hints ---------------------------------------

    @PostMapping("/oracle/hint")
    public Map<String, Object> oracleHint(
            @RequestParam(defaultValue = "42") long customerId,
            @RequestParam(defaultValue = "INDEX") String hint) {

        JdbcTemplate ora = oracleJdbc.getIfAvailable();
        ExplainOracle explain = explainOracleProvider.getIfAvailable();
        if (ora == null || explain == null) {
            throw new IllegalStateException("Oracle datasource not enabled — start with --oracle.enabled=true");
        }

        String hintFragment = switch (hint.toUpperCase()) {
            case "FULL"  -> "/*+ FULL(orders) */";
            case "INDEX" -> "/*+ INDEX(orders idx_orders_customer) */";
            case "NONE"  -> "";
            default      -> throw new IllegalArgumentException("hint must be FULL, INDEX, or NONE");
        };

        String sql = "SELECT " + hintFragment + " * FROM orders WHERE customer_id = " + customerId;
        Map<String, Object> plan = explain.explainWithActuals(sql);
        Map<String, Object> out = new LinkedHashMap<>(plan);
        out.put("hint", hint);
        out.put("lesson",
                "Hints OVERRIDE the optimizer. Try hint=FULL to force TABLE ACCESS " +
                "FULL, then hint=INDEX to force INDEX RANGE SCAN, and compare A-Rows " +
                "(actual) and Buffers. If FULL is faster than INDEX, your stats " +
                "are wrong or the table is small enough that the optimizer's " +
                "choice depends on DB_FILE_MULTIBLOCK_READ_COUNT.");
        return out;
    }

    // ----- Convenience GETs so you can hit these from a browser ----------

    @GetMapping("/disable-seqscan") public Map<String, Object> disableGet() { return disableSeqScan(); }
    @GetMapping("/enable-seqscan")  public Map<String, Object> enableGet()  { return enableSeqScan(); }
}
