package com.claude.dbpoc.m26.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The most common refresh strategy in practice: scheduled.
 *
 *   - For dashboards that tolerate "data is up to 1 minute stale",
 *     run REFRESH CONCURRENTLY every 60s.
 *   - For nightly reports, run it once and don't worry about
 *     blocking.
 *
 * This bean fires every {@code mv.refresh-interval-ms} ms and runs the
 * concurrent refresh. We track last-success and last-error so the
 * controller can show "how stale is my data?".
 *
 * Quietly handles the "MV doesn't exist yet" case — until the first
 * /mv/create-mv call, the refresh is a no-op.
 */
@Component
public class ScheduledRefresher {

    private final JdbcTemplate jdbc;
    private final AtomicLong lastSuccessMs = new AtomicLong();
    private final AtomicLong lastDurationMs = new AtomicLong();
    private volatile String lastError;

    public ScheduledRefresher(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelayString = "${mv.refresh-interval-ms:60000}")
    public void refresh() {
        try {
            Integer mvExists = jdbc.queryForObject(
                "select count(*) from pg_matviews where matviewname = 'monthly_sales'",
                Integer.class);
            if (mvExists == null || mvExists == 0) return;

            long t0 = System.nanoTime();
            jdbc.execute("refresh materialized view concurrently monthly_sales");
            lastDurationMs.set((System.nanoTime() - t0) / 1_000_000L);
            lastSuccessMs.set(System.currentTimeMillis());
            lastError = null;
        } catch (Exception e) {
            lastError = e.getMessage();
        }
    }

    public long getLastSuccessMs()  { return lastSuccessMs.get(); }
    public long getLastDurationMs() { return lastDurationMs.get(); }
    public String getLastError()    { return lastError; }
}
