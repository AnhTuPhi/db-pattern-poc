package com.claude.dbpoc.m01;

import com.claude.dbpoc.common.Timing;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk-inserts realistic-ish event data so the index demos are meaningful.
 *
 * Distributions are chosen deliberately:
 *   - 100k distinct user_ids over 1M rows  -> ~10 rows / user, so a B-tree
 *     lookup on user_id is highly selective (perfect index candidate).
 *   - 5 event_types with PAGE_VIEW = ~50%  -> shows why the planner picks
 *     a Seq Scan over an index for that value (selectivity too low).
 *   - 4 statuses with PENDING = ~2%        -> the partial-index sweet spot.
 *   - search_text is mostly random words + a small set of "marker" words
 *     so LIKE '%foo%' queries return a known-size result set.
 */
@RestController
@RequestMapping("/seed")
public class SeedController {

    private static final String[] EVENT_TYPES = {
        // Weighted by occurrence below — index here just gives us the labels.
        "PAGE_VIEW", "CLICK", "PURCHASE", "SIGN_UP", "LOGOUT"
    };
    private static final String[] STATUSES = {"OK", "FAILED", "RETRY", "PENDING"};
    private static final String[] WORDS = {
        "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
        "india", "juliet", "kilo", "lima", "mike", "november", "oscar", "papa",
        "quebec", "romeo", "sierra", "tango", "uniform", "victor", "whisky",
        "xray", "yankee", "zulu", "Foo", "Bar", "Baz"
    };

    /** Per JDBC docs, ~1000 is the sweet spot for Postgres rewriteBatchedInserts. */
    private static final int BATCH_SIZE = 1_000;

    private final JdbcTemplate jdbc;

    public SeedController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping
    public Map<String, Object> seed(@RequestParam(defaultValue = "1000000") int rows) {
        // Start clean so re-seeding doesn't double the row count.
        jdbc.execute("TRUNCATE TABLE events RESTART IDENTITY");

        String sql = "INSERT INTO events "
            + "(user_id, event_type, status, amount, created_at, payload, search_text) "
            + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)";

        Timing.Result res = Timing.measureVoid("seed-" + rows, 1, () -> {
            int remaining = rows;
            int offset = 0;
            while (remaining > 0) {
                final int chunk = Math.min(BATCH_SIZE, remaining);
                final int base = offset;
                jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                    @Override public int getBatchSize() { return chunk; }
                    @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ThreadLocalRandom r = ThreadLocalRandom.current();
                        long rowIdx = (long) base + i;

                        // 100k distinct users so each user has ~10 rows in a 1M dataset.
                        long userId = (r.nextLong(100_000)) + 1;

                        String eventType = pickEventType(r);
                        String status = pickStatus(r);

                        // Spread created_at over the last 365 days so range scans matter.
                        Instant ts = Instant.now().minus(r.nextLong(365 * 24L * 60), ChronoUnit.MINUTES);

                        // Cheap unique-ish JSON; not used in benches but realistic payload weight.
                        String json = "{\"v\":" + r.nextInt(1000) + ",\"u\":" + userId + "}";

                        // search_text: a few random words; occasionally inject "Foo" so the
                        // LIKE/ILIKE bench returns a known, non-empty result set.
                        String text = WORDS[r.nextInt(WORDS.length)] + " "
                                    + WORDS[r.nextInt(WORDS.length)] + " "
                                    + WORDS[r.nextInt(WORDS.length)] + " #" + rowIdx;

                        ps.setLong(1, userId);
                        ps.setString(2, eventType);
                        ps.setString(3, status);
                        ps.setBigDecimal(4, java.math.BigDecimal.valueOf(r.nextDouble(0, 500)).setScale(2, java.math.RoundingMode.HALF_UP));
                        ps.setTimestamp(5, Timestamp.from(ts));
                        ps.setString(6, json);
                        ps.setString(7, text);
                    }
                });
                remaining -= chunk;
                offset += chunk;
            }

            // Critical: without ANALYZE the planner uses stale stats and picks bad plans.
            jdbc.execute("ANALYZE events");
        });

        Map<String, Object> out = new HashMap<>();
        out.put("rows", rows);
        out.put("batchSize", BATCH_SIZE);
        out.put("totalMs", res.totalMillis());
        out.put("rowsPerSec", rows / Math.max(0.001, res.totalMillis() / 1000.0));
        out.put("note", "ANALYZE was run after the inserts so the planner has fresh stats.");
        return out;
    }

    /** Weighted draw — PAGE_VIEW dominates so seq-scan beats index for that value. */
    private static String pickEventType(ThreadLocalRandom r) {
        int x = r.nextInt(100);
        if (x < 50) return "PAGE_VIEW";
        if (x < 75) return "CLICK";
        if (x < 90) return "PURCHASE";
        if (x < 97) return "SIGN_UP";
        return "LOGOUT";
    }

    /** PENDING is rare (~2%) — that's exactly why the partial index shines. */
    private static String pickStatus(ThreadLocalRandom r) {
        int x = r.nextInt(100);
        if (x < 70) return "OK";
        if (x < 90) return "FAILED";
        if (x < 98) return "RETRY";
        return "PENDING";
    }
}
