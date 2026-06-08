package com.claude.dbpoc.m03;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "useful" endpoints — the ones an SRE actually hits at 3am.
 *
 * /top         -> top-N from pg_stat_statements (Postgres)
 * /reset       -> pg_stat_statements_reset(), for clean experiments
 * /top/oracle  -> equivalent against V$SQL/V$SQLAREA (Oracle)
 */
@RestController
public class HunterController {

    /**
     * The canonical pg_stat_statements top-N. Kept as a string constant
     * because DBAs/SREs copy this into psql all the time — it should be
     * easy to grep out of the source. ORDER BY clause is whitelisted into
     * this template via {ORDER}; we never inline user input.
     *
     * Notes:
     *   - mean_exec_time = total_exec_time / calls.
     *   - shared_blks_read is the I/O-from-disk indicator; shared_blks_hit
     *     means the buffer cache saved you. Reading 100k blks per call
     *     while everyone else reads 5 = your suspect.
     *   - query is truncated to 200 chars to keep the JSON readable.
     */
    private static final String PG_TOP_SQL = """
        SELECT
            substr(query, 1, 200)                          AS query,
            calls,
            round(total_exec_time::numeric, 2)             AS total_exec_time_ms,
            round(mean_exec_time::numeric, 2)              AS mean_exec_time_ms,
            rows,
            shared_blks_read,
            shared_blks_hit
        FROM pg_stat_statements
        WHERE query NOT ILIKE '%pg_stat_statements%'    -- don't include the meta-query
          AND query NOT ILIKE '%pg_catalog.%'
        ORDER BY {ORDER} DESC
        LIMIT ?
        """;

    /**
     * Whitelist of order-by columns. Anything not in this set is rejected
     * to keep the query safe to template.
     */
    private static final Set<String> ALLOWED_ORDER = Set.of(
            "total_time", "mean_time", "calls");

    /**
     * Oracle V$SQL top-N. V$SQL keeps one row per cursor (child); V$SQLAREA
     * aggregates by sql_id which is usually what you want. Both views are
     * in-memory (the cursor cache); when something falls out you need AWR
     * (DBA_HIST_SQLSTAT). The query below targets V$SQL because it has
     * higher resolution; switch to V$SQLAREA on busy systems.
     *
     * elapsed_time/buffer_gets/disk_reads in V$SQL are in microseconds
     * and blocks respectively. We divide by executions to get a per-call
     * number that's directly comparable across queries.
     */
    private static final String ORACLE_TOP_SQL = """
        SELECT *
        FROM (
            SELECT
                SUBSTR(sql_text, 1, 200)                     AS sql_text,
                executions,
                ROUND(elapsed_time / 1000)                   AS total_elapsed_ms,
                ROUND(elapsed_time / NULLIF(executions, 0) / 1000, 2)
                                                             AS mean_elapsed_ms,
                ROUND(buffer_gets / NULLIF(executions, 0), 0)
                                                             AS buffer_gets_per_exec,
                ROUND(disk_reads  / NULLIF(executions, 0), 0)
                                                             AS disk_reads_per_exec,
                rows_processed,
                sql_id
            FROM   v$sql
            WHERE  parsing_schema_name = USER
              AND  sql_text NOT LIKE '%v$sql%'
              AND  sql_text NOT LIKE '%V$SQL%'
            ORDER BY elapsed_time DESC
        )
        WHERE rownum <= ?
        """;

    private final JdbcTemplate pg;
    private final JdbcTemplate oracle;

    public HunterController(
            JdbcTemplate pgJdbc,
            @Autowired(required = false) @Qualifier("oracleJdbc") JdbcTemplate oracleJdbc) {
        this.pg = pgJdbc;
        this.oracle = oracleJdbc; // null when oracle.enabled=false
    }

    @GetMapping("/top")
    public List<Map<String, Object>> top(
            @RequestParam(defaultValue = "10") int n,
            @RequestParam(defaultValue = "total_time") String order) {

        if (!ALLOWED_ORDER.contains(order)) {
            throw new IllegalArgumentException(
                    "order must be one of: " + ALLOWED_ORDER);
        }
        // Map friendly names to pg_stat_statements column names.
        String column = switch (order) {
            case "total_time" -> "total_exec_time";
            case "mean_time"  -> "mean_exec_time";
            case "calls"      -> "calls";
            default           -> "total_exec_time"; // unreachable
        };
        String sql = PG_TOP_SQL.replace("{ORDER}", column);
        return pg.queryForList(sql, n);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        pg.execute("SELECT pg_stat_statements_reset()");
        return Map.of("status", "ok", "view", "pg_stat_statements", "cleared", true);
    }

    @GetMapping("/top/oracle")
    public Object topOracle(@RequestParam(defaultValue = "10") int n) {
        if (oracle == null) {
            return Map.of(
                    "status", "disabled",
                    "hint",   "set oracle.enabled=true and restart");
        }
        return oracle.queryForList(ORACLE_TOP_SQL, n);
    }
}
