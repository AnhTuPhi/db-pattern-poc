package com.claude.dbpoc.m18.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The scenario: we're renaming `users.full_name` to `display_name`.
 * Many app instances are running. Cannot take downtime. Must support
 * BOTH old and new code being live simultaneously at every step.
 *
 * Six phases. At every phase, old code AND new code both work — that's
 * the invariant. If old code crashes after step 2, you can deploy back
 * to old. If new code crashes after step 3, you can deploy back too.
 *
 * The state is tracked in a single `migration_state` row so the
 * endpoints can report where we are.
 */
@Service
public class ExpandContractService {

    private final JdbcTemplate jdbc;

    public ExpandContractService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> reset() {
        jdbc.execute("drop table if exists users cascade");
        jdbc.execute("create table users (id bigserial primary key, full_name text not null)");
        jdbc.execute("insert into users(full_name) values " +
                     "('Ada Lovelace'), ('Grace Hopper'), ('Donald Knuth'), ('Edsger Dijkstra')");
        jdbc.execute("create table if not exists migration_state(phase int not null)");
        jdbc.execute("truncate migration_state");
        jdbc.execute("insert into migration_state(phase) values (0)");
        return Map.of("phase", 0, "phaseName", "BASELINE", "rows", countRows());
    }

    /**
     * Phase 1 — EXPAND. Add the new column. Default NULL, nullable. No
     * data yet. Old code is unaware. New code can read/write display_name
     * when present, fall back to full_name otherwise.
     */
    @Transactional
    public Map<String, Object> expand() {
        jdbc.execute("alter table users add column if not exists display_name text");
        setPhase(1);
        return phaseInfo(1, "EXPAND",
            "added column display_name. Nullable, default null. Old code unaffected.");
    }

    /**
     * Phase 2 — DUAL-WRITE. New code writes to BOTH columns. Old code
     * still writes only to full_name. Reads still preference full_name.
     * Existing rows have display_name = NULL until backfilled.
     */
    @Transactional
    public Map<String, Object> dualWrite(Long id, String name) {
        jdbc.update("update users set full_name = ?, display_name = ? where id = ?", name, name, id);
        setPhase(Math.max(2, currentPhase()));
        return phaseInfo(currentPhase(), "DUAL_WRITE",
            "user " + id + " updated to '" + name + "' in BOTH columns. " +
            "Old code can still read full_name; new code reads display_name when present.");
    }

    /**
     * Phase 3 — BACKFILL. Copy old → new for any row where new IS NULL.
     * This is the migration tool's job in production (pt-online-schema-change,
     * pg_repack, or a hand-rolled chunked UPDATE). Done in batches so
     * the table is never locked for long. Here we do it in one shot
     * because the data is tiny — in production NEVER do a single
     * unbounded UPDATE on a large hot table.
     */
    @Transactional
    public Map<String, Object> backfill() {
        int updated = jdbc.update(
            "update users set display_name = full_name where display_name is null");
        setPhase(3);
        return phaseInfo(3, "BACKFILL",
            "copied full_name -> display_name on " + updated + " rows. " +
            "Now ALL rows have display_name populated. New code can rely on it.");
    }

    /**
     * Phase 4 — DUAL-READ. New code prefers display_name. Old code still
     * reads full_name. Writes go to both. Verify by reading: each row
     * returns BOTH columns and we report the preferred value.
     *
     * This is the safe window. If new code crashes here, OLD code still
     * works because full_name is still being written.
     */
    public Map<String, Object> dualRead() {
        if (currentPhase() < 3) return Map.of("error", "must backfill first");
        setPhase(Math.max(4, currentPhase()));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select id, full_name, display_name, " +
            "coalesce(display_name, full_name) as preferred " +
            "from users order by id");
        Map<String, Object> out = phaseInfo(4, "DUAL_READ",
            "new code prefers display_name when present; falls back to full_name.");
        out.put("rows", rows);
        return out;
    }

    /**
     * Phase 5 — SWITCH READS / STOP DUAL-WRITE. New code reads ONLY
     * display_name. Old code is no longer deployed. Writes go to
     * display_name only.
     *
     * Before this step you must have CONFIRMED all instances are on the
     * new code. The rollout monitor / canary / blue-green guarantee
     * that. This is the irreversible step from the perspective of
     * being able to rollback to OLD code (which would read full_name
     * and miss the latest writes).
     */
    @Transactional
    public Map<String, Object> switchReadsToNew(Long id, String name) {
        if (currentPhase() < 4) return Map.of("error", "must complete dual-read first");
        jdbc.update("update users set display_name = ? where id = ?", name, id);
        setPhase(5);
        return phaseInfo(5, "SWITCH_READS",
            "wrote ONLY display_name on user " + id + ". " +
            "full_name on this row is now STALE. Old code would see the stale value. " +
            "This is the step where the rollback window closes.");
    }

    /**
     * Phase 6 — CONTRACT. Drop the old column. The migration is complete.
     * From now on display_name is the only source of truth. Any code
     * referring to full_name will fail.
     */
    @Transactional
    public Map<String, Object> contract() {
        if (currentPhase() < 5) return Map.of("error", "must switch reads first");
        jdbc.execute("alter table users drop column full_name");
        setPhase(6);
        return phaseInfo(6, "CONTRACT", "dropped full_name. Migration complete.");
    }

    public Map<String, Object> describe() {
        int phase = currentPhase();
        List<Map<String, Object>> cols = jdbc.queryForList(
            "select column_name, data_type, is_nullable " +
            "from information_schema.columns " +
            "where table_schema = current_schema() and table_name = 'users' " +
            "order by ordinal_position");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", phase);
        out.put("phaseName", phaseName(phase));
        out.put("columns", cols);
        if (phase >= 1) {
            out.put("rows", jdbc.queryForList("select * from users order by id"));
        }
        return out;
    }

    // ---- helpers --------------------------------------------------------

    private int currentPhase() {
        Integer p = jdbc.queryForObject("select phase from migration_state", Integer.class);
        return p == null ? 0 : p;
    }

    private void setPhase(int p) {
        jdbc.update("update migration_state set phase = ?", p);
    }

    private Map<String, Object> phaseInfo(int p, String name, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", p);
        m.put("phaseName", name);
        m.put("note", note);
        return m;
    }

    private String phaseName(int p) {
        return switch (p) {
            case 0 -> "BASELINE";
            case 1 -> "EXPAND";
            case 2 -> "DUAL_WRITE";
            case 3 -> "BACKFILL";
            case 4 -> "DUAL_READ";
            case 5 -> "SWITCH_READS";
            case 6 -> "CONTRACT";
            default -> "UNKNOWN";
        };
    }

    private int countRows() {
        Integer c = jdbc.queryForObject("select count(*) from users", Integer.class);
        return c == null ? 0 : c;
    }
}
