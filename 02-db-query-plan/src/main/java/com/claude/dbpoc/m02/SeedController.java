package com.claude.dbpoc.m02;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * POST /seed?rows=N&db=pg|oracle
 *
 * Recreates the schema (idempotent) and inserts N rows via JdbcTemplate batch.
 * Status distribution is deliberately skewed (~70% PAID) so the planner's
 * selectivity estimate for `WHERE status = 'PAID'` lands near "most of the
 * table" — the threshold where Postgres flips from Index Scan to Seq Scan.
 *
 * We also run ANALYZE at the end because without fresh stats the planner is
 * working from defaults and the whole demo falls apart.
 */
@RestController
@RequestMapping("/seed")
public class SeedController {

    private static final int BATCH_SIZE = 5_000;
    private static final String[] STATUSES_WEIGHTED = buildWeightedStatuses();
    // ~700 distinct customers — enough that Bitmap Heap Scan becomes plausible
    // when we IN-list a handful of them, but few enough that a single customer
    // still has ~1.4k rows and Index Scan is clearly the right choice.
    private static final int CUSTOMER_COUNT = 700;

    private final JdbcTemplate pg;
    private final ObjectProvider<JdbcTemplate> oracleProvider;

    public SeedController(@Qualifier("pgJdbc") JdbcTemplate pg,
                          @Qualifier("oracleJdbc") ObjectProvider<JdbcTemplate> oracleProvider) {
        this.pg = pg;
        this.oracleProvider = oracleProvider;
    }

    @PostMapping
    public Map<String, Object> seed(@RequestParam(defaultValue = "1000000") int rows,
                                    @RequestParam(defaultValue = "pg") String db) throws IOException {
        long t0 = System.nanoTime();
        JdbcTemplate target;
        String schemaScript;

        if ("oracle".equalsIgnoreCase(db)) {
            target = oracleProvider.getIfAvailable();
            if (target == null) {
                throw new IllegalStateException(
                        "Oracle datasource not enabled — start with --oracle.enabled=true");
            }
            schemaScript = "schema-oracle.sql";
        } else {
            target = pg;
            schemaScript = "schema-pg.sql";
        }

        // 1. (Re)create schema. Idempotent — the SQL uses IF NOT EXISTS / -955 guards.
        applySchema(target, schemaScript);

        // 2. Truncate to make seeding reproducible. TRUNCATE is dramatically
        //    faster than DELETE and resets identity sequences in both engines.
        target.execute("TRUNCATE TABLE orders");

        // 3. Batched insert. 5k rows per batch is a sweet spot for Postgres
        //    (one round-trip per batch) without ballooning JDBC heap.
        int inserted = 0;
        for (int from = 0; from < rows; from += BATCH_SIZE) {
            final int batchSize = Math.min(BATCH_SIZE, rows - from);
            target.batchUpdate(
                    "INSERT INTO orders(customer_id, status, total) VALUES (?, ?, ?)",
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                            ThreadLocalRandom rng = ThreadLocalRandom.current();
                            ps.setLong(1, rng.nextLong(1, CUSTOMER_COUNT + 1));
                            ps.setString(2, STATUSES_WEIGHTED[rng.nextInt(STATUSES_WEIGHTED.length)]);
                            ps.setDouble(3, Math.round(rng.nextDouble(5, 5000) * 100.0) / 100.0);
                        }
                        @Override public int getBatchSize() { return batchSize; }
                    });
            inserted += batchSize;
        }

        // 4. Refresh stats. WITHOUT this, the planner is guessing — and bad
        //    guesses are the #1 root cause of bad plans (foreshadow module 04).
        if ("oracle".equalsIgnoreCase(db)) {
            target.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'ORDERS'); END;");
        } else {
            target.execute("ANALYZE orders");
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", db);
        out.put("rowsInserted", inserted);
        out.put("distinctCustomers", CUSTOMER_COUNT);
        out.put("statusDistribution", "PAID~70%, PENDING~20%, SHIPPED~7%, CANCELLED~3%");
        out.put("elapsedMs", elapsedMs);
        out.put("next", "try GET /plans/seq-scan, /plans/index-scan?customerId=42, etc.");
        return out;
    }

    private void applySchema(JdbcTemplate jdbc, String scriptName) throws IOException {
        Resource r = new ClassPathResource(scriptName);
        String sql = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute((java.sql.Connection c) -> {
            // ScriptUtils handles statement splitting for both ';' and PL/SQL '/'.
            ScriptUtils.executeSqlScript(c, new org.springframework.core.io.ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            return null;
        });
    }

    /** PAID 70 / PENDING 20 / SHIPPED 7 / CANCELLED 3, repeated to weight RNG. */
    private static String[] buildWeightedStatuses() {
        String[] out = new String[100];
        int i = 0;
        for (; i < 70; i++) out[i] = "PAID";
        for (; i < 90; i++) out[i] = "PENDING";
        for (; i < 97; i++) out[i] = "SHIPPED";
        for (; i < 100; i++) out[i] = "CANCELLED";
        return out;
    }
}
