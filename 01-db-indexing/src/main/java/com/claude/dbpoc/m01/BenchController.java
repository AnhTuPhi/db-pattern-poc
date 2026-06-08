package com.claude.dbpoc.m01;

import com.claude.dbpoc.common.Timing;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Benchmarks for each index pattern. Every endpoint follows the same shape:
 *
 *   1. Capture an EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) plan once.
 *   2. Time N executions of the same query via the shared Timing helper.
 *   3. Return both, plus a 1-line plan summary and the node-chain.
 *
 * The README workflow is "bench → CREATE INDEX → bench again" and compares
 * the two responses side-by-side.
 */
@RestController
@RequestMapping("/bench")
public class BenchController {

    /** 20 runs: enough to smooth out cold-cache noise, fast enough to feel interactive. */
    private static final int RUNS = 20;

    private final JdbcTemplate jdbc;
    private final ExplainRunner explain;

    public BenchController(JdbcTemplate jdbc, ExplainRunner explain) {
        this.jdbc = jdbc;
        this.explain = explain;
    }

    // ----- B-tree lookup ------------------------------------------------------

    @GetMapping("/btree")
    public Map<String, Object> btree(@RequestParam(defaultValue = "42") long userId) {
        String sql = "SELECT * FROM events WHERE user_id = ?";
        return benchOne("btree-user-id", sql, new Object[]{userId});
    }

    /**
     * Demonstrates "low selectivity beats the index": PAGE_VIEW is ~50% of
     * rows, so even with an index on event_type the planner picks Seq Scan.
     */
    @GetMapping("/btree-low-selectivity")
    public Map<String, Object> btreeLowSelectivity(@RequestParam(defaultValue = "PAGE_VIEW") String eventType) {
        String sql = "SELECT id, user_id FROM events WHERE event_type = ?";
        return benchOne("btree-low-selectivity", sql, new Object[]{eventType});
    }

    // ----- Covering / index-only scan -----------------------------------------

    @GetMapping("/covering")
    public Map<String, Object> covering(@RequestParam(defaultValue = "42") long userId) {
        // Only project columns that exist in the covering index (user_id, status, amount)
        // — that's the magic that lets Postgres skip touching the heap entirely.
        String sql = "SELECT user_id, status, amount FROM events WHERE user_id = ?";
        return benchOne("covering-index-only", sql, new Object[]{userId});
    }

    // ----- Partial ------------------------------------------------------------

    @GetMapping("/partial")
    public Map<String, Object> partial() {
        // PENDING is ~2% of rows; ORDER BY + LIMIT is what the partial index speeds up.
        String sql = "SELECT * FROM events WHERE status = 'PENDING' ORDER BY created_at DESC LIMIT 50";
        return benchOne("partial-pending", sql, new Object[]{});
    }

    // ----- Functional ---------------------------------------------------------

    @GetMapping("/functional")
    public Map<String, Object> functional(@RequestParam(defaultValue = "Foo") String q) {
        // LOWER() on the column defeats any plain B-tree on search_text. Only the
        // functional index on LOWER(search_text) can be used.
        String sql = "SELECT id, search_text FROM events WHERE LOWER(search_text) = LOWER(?)";
        return benchOne("functional-lower", sql, new Object[]{q});
    }

    // ----- GIN trigram --------------------------------------------------------

    @GetMapping("/gin-trigram")
    public Map<String, Object> ginTrigram(@RequestParam(defaultValue = "foo") String q) {
        // %term% — leading wildcard. B-tree is useless; GIN trigram is the answer.
        String sql = "SELECT id, search_text FROM events WHERE search_text ILIKE ?";
        return benchOne("gin-trigram-ilike", sql, new Object[]{"%" + q + "%"});
    }

    // ----- shared bench shape -------------------------------------------------

    private Map<String, Object> benchOne(String label, String sql, Object[] params) {
        // 1) one EXPLAIN run for the plan
        JsonNode plan = explain.run(sql, params);

        // 2) RUNS hot runs through queryForList to materialise results (no streaming cheat).
        Timing.Result timing = Timing.measure(label, RUNS,
            () -> jdbc.queryForList(sql, params));

        // 3) format response
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        out.put("sql", sql);
        out.put("params", params);
        out.put("planSummary", explain.summary(plan));
        out.put("planChain", explain.nodeChain(plan));
        out.put("rawPlan", plan);

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("runs", timing.iterations());
        t.put("totalMs", timing.totalMillis());
        t.put("avgMicros", timing.avgMicros());
        t.put("minMicros", timing.minNanos() / 1_000.0);
        t.put("maxMicros", timing.maxNanos() / 1_000.0);
        out.put("timing", t);
        return out;
    }
}
