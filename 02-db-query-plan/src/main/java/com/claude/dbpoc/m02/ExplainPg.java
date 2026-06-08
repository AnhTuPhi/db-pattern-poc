package com.claude.dbpoc.m02;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Postgres EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) and returns both:
 *   - The raw text — what a DBA sees when they paste into psql.
 *   - A coarse parse of the top operator: node type, cost, rows, time, buffers.
 *
 * Why TEXT format and not JSON? TEXT is what people actually read in production
 * (in logs, in psql, in pgAdmin) — and the goal of this module is to make that
 * text legible. JSON parses easier but trains the wrong reflex.
 *
 * IMPORTANT: ANALYZE actually EXECUTES the query. Don't point this at
 * `DELETE FROM orders` in prod. For SELECT statements that's exactly what we
 * want — planned vs actual rows is the headline metric.
 */
@Component
public class ExplainPg {

    private static final Pattern NODE_LINE = Pattern.compile(
            "^\\s*(?:->\\s*)?(?<node>[A-Za-z][A-Za-z ]+?)\\s+" +
            "\\(cost=(?<startCost>[0-9.]+)\\.\\.(?<totalCost>[0-9.]+)\\s+" +
            "rows=(?<plannedRows>\\d+)\\s+width=(?<width>\\d+)\\)" +
            "(?:\\s+\\(actual time=(?<startTime>[0-9.]+)\\.\\.(?<endTime>[0-9.]+)\\s+" +
            "rows=(?<actualRows>\\d+)\\s+loops=(?<loops>\\d+)\\))?");

    private static final Pattern BUFFERS_LINE = Pattern.compile(
            "Buffers:\\s+(?<line>.*)");

    private static final Pattern HEAP_FETCHES =
            Pattern.compile("Heap Fetches:\\s+(\\d+)");

    private final JdbcTemplate jdbc;

    public ExplainPg(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> explain(String sql, Object... params) {
        // queryForList over a SELECT EXPLAIN works — each output row is one
        // line of the plan text in column "QUERY PLAN".
        List<String> lines = jdbc.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT TEXT) " + sql,
                String.class,
                params);

        String raw = String.join("\n", lines);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sql", sql);
        out.put("raw", raw);
        out.put("parsed", parseTopNode(lines));
        return out;
    }

    /** Best-effort parse of the first plan line + any Buffers / Heap Fetches lines. */
    private Map<String, Object> parseTopNode(List<String> lines) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        for (String line : lines) {
            Matcher m = NODE_LINE.matcher(line);
            if (m.find() && !parsed.containsKey("nodeType")) {
                parsed.put("nodeType", m.group("node").trim());
                parsed.put("startupCost", Double.parseDouble(m.group("startCost")));
                parsed.put("totalCost", Double.parseDouble(m.group("totalCost")));
                parsed.put("plannedRows", Long.parseLong(m.group("plannedRows")));
                parsed.put("width", Integer.parseInt(m.group("width")));
                if (m.group("actualRows") != null) {
                    parsed.put("actualStartMs", Double.parseDouble(m.group("startTime")));
                    parsed.put("actualEndMs", Double.parseDouble(m.group("endTime")));
                    parsed.put("actualRows", Long.parseLong(m.group("actualRows")));
                    parsed.put("loops", Long.parseLong(m.group("loops")));

                    long planned = (Long) parsed.get("plannedRows");
                    long actual = (Long) parsed.get("actualRows");
                    // The cardinality lesson (module 04 preview): if planned and
                    // actual disagree by more than ~10x, the planner was choosing
                    // its plan in the dark.
                    if (planned > 0 && (actual > planned * 10 || planned > Math.max(actual, 1) * 10)) {
                        notes.add(String.format(
                                "Cardinality mismatch: planned=%d, actual=%d — stats likely stale.",
                                planned, actual));
                    }
                }
            }
            Matcher b = BUFFERS_LINE.matcher(line);
            if (b.find() && !parsed.containsKey("buffers")) {
                parsed.put("buffers", b.group("line").trim());
            }
            Matcher h = HEAP_FETCHES.matcher(line);
            if (h.find()) {
                long hf = Long.parseLong(h.group(1));
                parsed.put("heapFetches", hf);
                if (hf == 0) {
                    notes.add("Heap Fetches: 0 — index-only scan succeeded (visibility map covered all tuples).");
                }
            }
        }
        if (!notes.isEmpty()) parsed.put("notes", notes);
        return parsed;
    }
}
