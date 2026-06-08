package com.claude.dbpoc.m01;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`.
 *
 * Every bench endpoint calls run() once to capture the plan, then runs the
 * query N times for the timing average. We use FORMAT JSON because text plans
 * are nice for humans but a pain to parse — JSON gives us {plan, planning_time,
 * execution_time, ...} cleanly.
 */
@Component
public class ExplainRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ExplainRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the parsed JSON plan (Postgres always returns a one-element array). */
    public JsonNode run(String sql, Object... params) {
        String explained = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql;
        // queryForObject(..., String.class) returns the entire JSON array as text.
        String json = jdbc.queryForObject(explained, String.class, params);
        try {
            JsonNode arr = MAPPER.readTree(json);
            // Pg wraps the plan in a one-element array; flatten so callers can read .get("Plan").
            return arr.isArray() && arr.size() == 1 ? arr.get(0) : arr;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse EXPLAIN JSON: " + json, e);
        }
    }

    /**
     * One-line summary of the root Plan node, plus the planning + execution times.
     * Handy for a quick "did the planner pick the right scan?" check in the REST response.
     */
    public String summary(JsonNode explain) {
        JsonNode plan = explain.get("Plan");
        if (plan == null) return "<no Plan node>";

        String nodeType = plan.path("Node Type").asText("?");
        String indexName = plan.path("Index Name").asText("");
        double actualMs = plan.path("Actual Total Time").asDouble();
        long rows = plan.path("Actual Rows").asLong();

        double planningMs = explain.path("Planning Time").asDouble();
        double executionMs = explain.path("Execution Time").asDouble();

        StringBuilder s = new StringBuilder()
            .append(nodeType);
        if (!indexName.isEmpty()) s.append(" using ").append(indexName);
        s.append(String.format(
            " | actual=%.3fms rows=%d | planning=%.3fms exec=%.3fms",
            actualMs, rows, planningMs, executionMs));
        return s.toString();
    }

    /**
     * Walk the plan tree depth-first, collecting node summaries. Lets the README
     * call out things like "see — it's an Index Only Scan, Heap Fetches: 0".
     */
    public List<String> nodeChain(JsonNode explain) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        collect(explain.get("Plan"), 0, out);
        return out;
    }

    private void collect(JsonNode node, int depth, java.util.List<String> out) {
        if (node == null) return;
        String indent = "  ".repeat(depth);
        String type = node.path("Node Type").asText("?");
        String idx = node.path("Index Name").asText("");
        long heapFetches = node.path("Heap Fetches").asLong(-1);
        double actual = node.path("Actual Total Time").asDouble();
        long rows = node.path("Actual Rows").asLong();

        StringBuilder s = new StringBuilder(indent).append("-> ").append(type);
        if (!idx.isEmpty()) s.append(" [").append(idx).append("]");
        s.append(String.format(" actual=%.3fms rows=%d", actual, rows));
        if (heapFetches >= 0) s.append(" heap_fetches=").append(heapFetches);
        out.add(s.toString());

        JsonNode children = node.get("Plans");
        if (children != null && children.isArray()) {
            for (JsonNode c : children) collect(c, depth + 1, out);
        }
    }
}
