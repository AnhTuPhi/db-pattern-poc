package com.claude.dbpoc.m03;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pattern-based suggestions on top of the catalog views.
 *
 * The "find unindexed FKs" query is one of the most-shared snippets in
 * Postgres DBA circles for a reason: it's the lowest-effort, highest-value
 * fix you can ship in production. We expose it as JSON and as ready-to-paste
 * CREATE INDEX statements.
 *
 * The Oracle equivalent uses DBA_CONSTRAINTS / DBA_IND_COLUMNS — the user
 * must have SELECT on those views (most app accounts don't; flip to
 * USER_CONSTRAINTS / USER_IND_COLUMNS if so).
 */
@RestController
public class SuggestionController {

    /**
     * Find foreign keys whose leading column is NOT covered by any index.
     *
     * The check is: for every FK constraint in a user schema, look for ANY
     * index whose first column matches the FK's first column. If none
     * exists, the FK is unindexed — Postgres has to Seq Scan the child
     * table on every parent UPDATE/DELETE *and* on the natural lookup
     * pattern `WHERE fk_col = ?`.
     *
     * Standard DBA snippet; safe to paste into psql against any database.
     */
    private static final String PG_UNINDEXED_FKS_CLEAN = """
        SELECT
            (conrelid::regclass)::text          AS table_name,
            conname                             AS constraint_name,
            a.attname                           AS column_name,
            (confrelid::regclass)::text         AS references_table
        FROM   pg_constraint c
        JOIN   pg_namespace  n ON n.oid = c.connamespace
        JOIN   pg_attribute  a ON a.attrelid = c.conrelid
                              AND a.attnum   = c.conkey[1]
        WHERE  c.contype = 'f'
          AND  n.nspname NOT IN ('pg_catalog', 'information_schema')
          AND  NOT EXISTS (
              SELECT 1
              FROM   pg_index i
              WHERE  i.indrelid = c.conrelid
                AND  i.indkey[0] = c.conkey[1]
          )
        ORDER BY (conrelid::regclass)::text, conname
        """;

    /**
     * Oracle: foreign-key constraints whose first column has no index that
     * starts with that column. Standard DBA snippet, paraphrased.
     */
    private static final String ORACLE_UNINDEXED_FKS = """
        SELECT
            c.table_name,
            c.constraint_name,
            cc.column_name,
            c.r_constraint_name AS references_constraint
        FROM   user_constraints c
        JOIN   user_cons_columns cc
               ON cc.constraint_name = c.constraint_name
              AND cc.position        = 1
        WHERE  c.constraint_type = 'R'
          AND  NOT EXISTS (
              SELECT 1
              FROM   user_ind_columns ic
              WHERE  ic.table_name   = c.table_name
                AND  ic.column_name  = cc.column_name
                AND  ic.column_position = 1
          )
        ORDER BY c.table_name, c.constraint_name
        """;

    private final JdbcTemplate pg;
    private final JdbcTemplate oracle;

    public SuggestionController(
            JdbcTemplate pgJdbc,
            @Autowired(required = false) @Qualifier("oracleJdbc") JdbcTemplate oracleJdbc) {
        this.pg = pgJdbc;
        this.oracle = oracleJdbc;
    }

    @GetMapping("/suggest/missing-fk-indexes")
    public Map<String, Object> pgMissingFkIndexes() {
        List<Map<String, Object>> rows = pg.queryForList(PG_UNINDEXED_FKS_CLEAN);

        List<String> ddl = rows.stream()
                .map(r -> "CREATE INDEX CONCURRENTLY ON %s (%s);".formatted(
                        r.get("table_name"), r.get("column_name")))
                .collect(Collectors.toList());

        return Map.of(
                "count", rows.size(),
                "missing_fk_indexes", rows,
                "suggested_ddl", ddl);
    }

    @GetMapping("/suggest/oracle/missing-fk-indexes")
    public Object oracleMissingFkIndexes() {
        if (oracle == null) {
            return Map.of(
                    "status", "disabled",
                    "hint",   "set oracle.enabled=true and restart");
        }
        List<Map<String, Object>> rows = oracle.queryForList(ORACLE_UNINDEXED_FKS);

        List<String> ddl = rows.stream()
                .map(r -> "CREATE INDEX %s_idx ON %s (%s);".formatted(
                        r.get("CONSTRAINT_NAME"),
                        r.get("TABLE_NAME"),
                        r.get("COLUMN_NAME")))
                .collect(Collectors.toList());

        return Map.of(
                "count", rows.size(),
                "missing_fk_indexes", rows,
                "suggested_ddl", ddl);
    }
}
