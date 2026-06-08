package com.claude.dbpoc.m02;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle equivalent of ExplainPg.
 *
 * Oracle's workflow is TWO STEPS:
 *   1. EXPLAIN PLAN FOR <sql>   -- writes the plan into PLAN_TABLE
 *   2. SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(...))  -- pretty-prints it
 *
 * We use format 'ALLSTATS LAST' which is the closest analog to Postgres'
 * ANALYZE — it shows actual rows + actual time. BUT: ALLSTATS only has data
 * when the SQL has been executed at least once and STATISTICS_LEVEL = ALL
 * (or the /*+ GATHER_PLAN_STATISTICS *\/ hint was used). For the cold
 * EXPLAIN PLAN form we fall back to plain 'TYPICAL'.
 *
 * Only registered as a bean when oracle.enabled=true to keep the module
 * runnable with just Postgres up.
 */
@Component
@ConditionalOnProperty(name = "oracle.enabled", havingValue = "true")
public class ExplainOracle {

    private final JdbcTemplate oracle;

    public ExplainOracle(JdbcTemplate oracleJdbc) {
        this.oracle = oracleJdbc;
    }

    /**
     * Cold EXPLAIN PLAN — does NOT execute the query. Useful when you can't
     * afford to run the SQL (e.g. it's a destructive DML or takes hours).
     */
    public Map<String, Object> explainPlan(String sql, Object... params) {
        // Bind variables in EXPLAIN PLAN are tricky: the planner sees them as
        // unknown constants and uses default selectivity (5% for equality).
        // For accurate plans, inline literal values OR use ALLSTATS LAST after
        // executing the SQL once.
        oracle.update("EXPLAIN PLAN SET STATEMENT_ID = 'm02_poc' FOR " + sql, params);
        List<String> lines = oracle.queryForList(
                "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', 'm02_poc', 'TYPICAL'))",
                String.class);
        return wrap(sql, lines, "EXPLAIN PLAN (no actuals — query not executed)");
    }

    /**
     * Hot ALLSTATS path — actually runs the query (with GATHER_PLAN_STATISTICS),
     * then asks the cursor cache for the last plan + actual row counts.
     */
    public Map<String, Object> explainWithActuals(String sql, Object... params) {
        // Force per-row plan stats so DISPLAY_CURSOR can report actuals.
        String hinted = "SELECT /*+ GATHER_PLAN_STATISTICS */ * FROM (" + sql + ")";
        oracle.queryForList(hinted, params);
        List<String> lines = oracle.queryForList(
                "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(format => 'ALLSTATS LAST'))",
                String.class);
        return wrap(sql, lines, "DISPLAY_CURSOR ALLSTATS LAST (E-Rows = estimated, A-Rows = actual)");
    }

    private Map<String, Object> wrap(String sql, List<String> lines, String mode) {
        String raw = String.join("\n", lines);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sql", sql);
        out.put("mode", mode);
        out.put("raw", raw);
        return out;
    }
}
