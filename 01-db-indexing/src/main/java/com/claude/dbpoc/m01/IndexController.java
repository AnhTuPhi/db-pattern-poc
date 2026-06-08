package com.claude.dbpoc.m01;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD on the indexes themselves. Each create/drop endpoint is split out so the
 * README can demo a clean "no-index bench → create one → re-bench" workflow.
 *
 * We use plain DDL strings (not parameterised) because index DDL doesn't
 * accept parameters and the values here are hard-coded.
 */
@RestController
@RequestMapping("/indexes")
public class IndexController {

    /** Every index name we know about, so DELETE /indexes can clean up reliably. */
    private static final List<String> ALL_INDEX_NAMES = List.of(
        "idx_events_user_id",
        "idx_events_user_created",
        "idx_events_created_user",   // the "wrong column order" demo
        "idx_events_user_covering",
        "idx_events_pending_partial",
        "idx_events_lower_search_text",
        "idx_events_search_text_plain",  // baseline B-tree for the functional-index lesson
        "idx_events_search_trgm"
    );

    private final JdbcTemplate jdbc;

    public IndexController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ----- CREATE -------------------------------------------------------------

    @PostMapping("/btree")
    public Map<String, Object> createBtree() {
        // The basic B-tree: every user_id lookup gets an index scan.
        return runDdl("idx_events_user_id",
            "CREATE INDEX idx_events_user_id ON events (user_id)");
    }

    /** Two variants so the README can show that column order matters. */
    @PostMapping("/composite-good")
    public Map<String, Object> createCompositeGood() {
        // (user_id, created_at DESC) — leading column matches WHERE user_id = ?
        return runDdl("idx_events_user_created",
            "CREATE INDEX idx_events_user_created ON events (user_id, created_at DESC)");
    }

    @PostMapping("/composite-bad")
    public Map<String, Object> createCompositeBad() {
        // Leading column is created_at — useless for WHERE user_id = ?
        return runDdl("idx_events_created_user",
            "CREATE INDEX idx_events_created_user ON events (created_at DESC, user_id)");
    }

    @PostMapping("/covering")
    public Map<String, Object> createCovering() {
        // INCLUDE columns ride along in the index leaf -> index-only scan, Heap Fetches: 0.
        return runDdl("idx_events_user_covering",
            "CREATE INDEX idx_events_user_covering ON events (user_id) INCLUDE (status, amount)");
    }

    @PostMapping("/partial")
    public Map<String, Object> createPartial() {
        // Only the ~2% PENDING rows are stored -> tiny, fast index for that predicate.
        return runDdl("idx_events_pending_partial",
            "CREATE INDEX idx_events_pending_partial ON events (created_at DESC) WHERE status = 'PENDING'");
    }

    @PostMapping("/functional")
    public Map<String, Object> createFunctional() {
        // Indexes LOWER(search_text). Required because the bench query wraps the
        // column in LOWER() — a plain B-tree on search_text would be unused.
        return runDdl("idx_events_lower_search_text",
            "CREATE INDEX idx_events_lower_search_text ON events (LOWER(search_text))");
    }

    @PostMapping("/plain-text")
    public Map<String, Object> createPlainText() {
        // Baseline B-tree on search_text — the bench shows the planner ignores
        // it once the WHERE clause is LOWER(search_text) = ?
        return runDdl("idx_events_search_text_plain",
            "CREATE INDEX idx_events_search_text_plain ON events (search_text)");
    }

    @PostMapping("/gin-trigram")
    public Map<String, Object> createGinTrigram() {
        // pg_trgm extension is enabled in docker/postgres-init.sql.
        return runDdl("idx_events_search_trgm",
            "CREATE INDEX idx_events_search_trgm ON events USING GIN (search_text gin_trgm_ops)");
    }

    // ----- READ / DROP --------------------------------------------------------

    /**
     * Lists every index on `events` in the current schema along with its on-disk
     * size. Size is what makes partial / covering tradeoffs concrete.
     */
    @GetMapping
    public List<Map<String, Object>> listIndexes() {
        String sql = """
            SELECT  i.indexname               AS name,
                    pg_relation_size(c.oid)   AS size_bytes,
                    pg_size_pretty(pg_relation_size(c.oid)) AS size_pretty,
                    i.indexdef                AS definition
            FROM    pg_indexes i
            JOIN    pg_class   c ON c.relname = i.indexname
            JOIN    pg_namespace n ON n.oid = c.relnamespace AND n.nspname = i.schemaname
            WHERE   i.tablename = 'events'
              AND   i.schemaname = current_schema()
            ORDER BY size_bytes DESC
            """;
        return jdbc.queryForList(sql);
    }

    /** Drop every index this module knows about. Primary key is left alone. */
    @DeleteMapping
    public Map<String, Object> dropAll() {
        List<String> dropped = new ArrayList<>();
        for (String name : ALL_INDEX_NAMES) {
            jdbc.execute("DROP INDEX IF EXISTS " + name);
            dropped.add(name);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dropped", dropped);
        out.put("note", "Primary key index is intentionally left intact.");
        return out;
    }

    // ----- helpers ------------------------------------------------------------

    private Map<String, Object> runDdl(String name, String ddl) {
        long t = System.nanoTime();
        jdbc.execute(ddl);
        // CREATE INDEX doesn't update stats; ANALYZE makes the planner notice it immediately.
        jdbc.execute("ANALYZE events");
        double ms = (System.nanoTime() - t) / 1_000_000.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("ddl", ddl);
        out.put("createMs", ms);
        return out;
    }
}
