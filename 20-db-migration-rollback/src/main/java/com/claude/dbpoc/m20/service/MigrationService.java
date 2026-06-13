package com.claude.dbpoc.m20.service;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Surface Flyway state from inside the running app — the
 * `flyway_schema_history` row history, the migrations Flyway "sees" on
 * the classpath, and the current state of the demo table.
 *
 * The point: Flyway is a state machine, and you read its state from one
 * table. There is no magic. Understanding that table is the entire
 * job of operating Flyway in production.
 */
@Service
public class MigrationService {

    private final Flyway flyway;
    private final JdbcTemplate jdbc;

    public MigrationService(Flyway flyway, JdbcTemplate jdbc) {
        this.flyway = flyway;
        this.jdbc = jdbc;
    }

    /**
     * The flyway_schema_history table — single source of truth for "what
     * has been applied to this database". This is what Flyway consults
     * on every startup.
     *
     *   installed_rank    monotonic install order
     *   version           V###__ part, or null for repeatable (R) migrations
     *   description       __XXX_yyy__ part of the filename
     *   type              SQL | JDBC | etc
     *   script            the filename
     *   checksum          hash of the contents at apply time. If you edit
     *                     a versioned migration after it's applied, the
     *                     checksum no longer matches and Flyway will
     *                     refuse to start unless you repair.
     *   installed_by      DB user that ran it
     *   installed_on      timestamp
     *   execution_time    ms
     *   success           always true once persisted; failures usually
     *                     leave no row (Flyway aborts the tx)
     */
    public Map<String, Object> history() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select installed_rank, version, description, type, script, " +
            "       checksum, installed_by, installed_on, execution_time, success " +
            "from flyway_schema_history order by installed_rank");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("note",
            "This is the source of truth. Flyway uses this to decide what to apply on next " +
            "boot. Hand-editing this table is sometimes necessary to clean up state, but it " +
            "is a load-bearing manual operation — back up the DB first.");
        return out;
    }

    /** Migration files Flyway sees, with applied/pending state. */
    public Map<String, Object> info() {
        MigrationInfo[] all = flyway.info().all();
        List<Map<String, Object>> rows = java.util.Arrays.stream(all)
            .map(m -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("version", m.getVersion() == null ? null : m.getVersion().getVersion());
                r.put("description", m.getDescription());
                r.put("type", m.getType().toString());
                r.put("state", m.getState().toString());
                r.put("script", m.getScript());
                r.put("installedOn", m.getInstalledOn() == null ? null : m.getInstalledOn().toString());
                return r;
            })
            .collect(Collectors.toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("migrations", rows);
        return out;
    }

    /** Current state of the demo table — for verifying migrations did what they claim. */
    public Map<String, Object> describe() {
        List<Map<String, Object>> cols = jdbc.queryForList(
            "select column_name, data_type, is_nullable, column_default " +
            "from information_schema.columns " +
            "where table_schema = current_schema() and table_name = 'product' " +
            "order by ordinal_position");
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("select * from product order by id");
        } catch (RuntimeException e) {
            rows = List.of(Map.of("error", e.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", cols);
        out.put("rows", rows);
        return out;
    }

    /** Trigger a fresh migrate at runtime. Useful when adding files in /db/migration/runtime. */
    public Map<String, Object> migrate() {
        var result = flyway.migrate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("migrationsExecuted", result.migrationsExecuted);
        out.put("targetSchemaVersion", result.targetSchemaVersion);
        out.put("success", result.success);
        return out;
    }
}
