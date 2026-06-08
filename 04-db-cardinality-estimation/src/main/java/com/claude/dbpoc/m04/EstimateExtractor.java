package com.claude.dbpoc.m04;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The one component the whole module is built around.
 *
 * Runs `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` for a given SQL, walks the
 * plan tree and yields a list of {nodeType, estimatedRows, actualRows, ratio}
 * entries. That ratio — Postgres' "Plan Rows" divided by "Actual Rows" — is the
 * single most useful number when chasing a "why did this query suddenly get
 * slow?" incident:
 *
 *   ratio ≈ 1.0   planner saw reality, plan choice is trustworthy
 *   ratio  ≫ 1    planner over-estimated   (often wastes work_mem, picks hash)
 *   ratio  ≪ 1    planner under-estimated  (the dangerous one — picks nested-loop
 *                                           for what is actually millions of rows)
 *
 * Returning this as JSON makes the lesson plain in every demo endpoint.
 */
@Component
public class EstimateExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public EstimateExtractor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One node in the plan tree, flattened for the REST response. */
    public record NodeEstimate(
            String nodeType,
            String indexName,
            long estimatedRows,
            long actualRows,
            double ratio,
            double actualTotalMs
    ) {}

    /** Whole-query result: plan choice summary + per-node estimates + raw JSON for the curious. */
    public record PlanReport(
            String rootNodeType,
            String rootIndex,
            long rootEstimatedRows,
            long rootActualRows,
            double rootRatioOff,
            double planningMs,
            double executionMs,
            List<NodeEstimate> nodes,
            JsonNode rawPlan
    ) {}

    public PlanReport explain(String sql, Object... params) {
        // FORMAT JSON because the text plan is unparseable noise. ANALYZE so we
        // get BOTH the planner's guess (Plan Rows) and reality (Actual Rows).
        String explained = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql;
        String json = jdbc.queryForObject(explained, String.class, params);

        JsonNode root;
        try {
            JsonNode arr = MAPPER.readTree(json);
            root = arr.isArray() && arr.size() == 1 ? arr.get(0) : arr;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse EXPLAIN JSON: " + json, e);
        }

        JsonNode plan = root.get("Plan");
        List<NodeEstimate> nodes = new ArrayList<>();
        collect(plan, nodes);

        NodeEstimate rootNode = nodes.isEmpty() ? null : nodes.get(0);
        return new PlanReport(
                rootNode == null ? "?" : rootNode.nodeType(),
                rootNode == null ? "" : rootNode.indexName(),
                rootNode == null ? 0 : rootNode.estimatedRows(),
                rootNode == null ? 0 : rootNode.actualRows(),
                rootNode == null ? 0 : rootNode.ratio(),
                root.path("Planning Time").asDouble(),
                root.path("Execution Time").asDouble(),
                nodes,
                root
        );
    }

    private void collect(JsonNode node, List<NodeEstimate> out) {
        if (node == null) return;

        long est = node.path("Plan Rows").asLong(0);
        long act = node.path("Actual Rows").asLong(0);
        // Round to two decimals so JSON readers don't see noise. Guard against /0.
        double ratio = act == 0
                ? (est == 0 ? 1.0 : Double.POSITIVE_INFINITY)
                : Math.round((est * 100.0) / act) / 100.0;

        out.add(new NodeEstimate(
                node.path("Node Type").asText("?"),
                node.path("Index Name").asText(""),
                est,
                act,
                Double.isInfinite(ratio) ? -1.0 : ratio,
                node.path("Actual Total Time").asDouble()
        ));

        JsonNode children = node.get("Plans");
        if (children != null && children.isArray()) {
            for (JsonNode c : children) collect(c, out);
        }
    }
}
