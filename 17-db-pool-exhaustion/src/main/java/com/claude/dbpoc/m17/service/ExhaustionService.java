package com.claude.dbpoc.m17.service;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Three demos:
 *
 *   1. cascade    — fire `slow` requests on the MAIN pool; observe that
 *                   "fast" requests on the same pool time out.
 *   2. retryStorm — wrap each failing fast request in a naive retry loop;
 *                   the storm makes the problem WORSE.
 *   3. bulkhead   — fire the slow requests on the SLOW pool (4 slots);
 *                   the slow pool starves itself but the main pool is
 *                   untouched. Fast requests stay fast.
 */
@Service
public class ExhaustionService {

    private final JdbcTemplate mainJdbc;
    private final JdbcTemplate slowJdbc;
    private final HikariDataSource mainDs;
    private final HikariDataSource slowDs;

    public ExhaustionService(
            @Qualifier("mainJdbc") JdbcTemplate mainJdbc,
            @Qualifier("slowJdbc") JdbcTemplate slowJdbc,
            @Qualifier("mainDs")   HikariDataSource mainDs,
            @Qualifier("slowDs")   HikariDataSource slowDs) {
        this.mainJdbc = mainJdbc;
        this.slowJdbc = slowJdbc;
        this.mainDs = mainDs;
        this.slowDs = slowDs;
    }

    // ---------------------------------------------------------------------
    // 1. CASCADE — slow code path saturates the shared pool.
    //
    // Fire `slowCount` concurrent slow queries on the MAIN pool. They hold
    // their connections for `slowMs` each. While they hold, fire
    // `fastCount` "real user" requests. With pool=10 and slowCount=10,
    // every fast request times out at connection-timeout.
    // ---------------------------------------------------------------------
    public Map<String, Object> cascade(int slowCount, long slowMs, int fastCount) {
        ExecutorService pool = Executors.newCachedThreadPool();
        AtomicInteger fastOk = new AtomicInteger();
        AtomicInteger fastTimedOut = new AtomicInteger();
        AtomicInteger slowOk = new AtomicInteger();
        AtomicInteger slowFailed = new AtomicInteger();

        long t0 = System.nanoTime();
        try {
            // Saturate.
            for (int i = 0; i < slowCount; i++) {
                pool.submit(() -> { runSlow(mainJdbc, slowMs, slowOk, slowFailed); });
            }
            // Give the slow queries a beat to actually grab their connections.
            Thread.sleep(50);
            // Now the "real users" hit it.
            Future<?>[] futs = new Future[fastCount];
            for (int i = 0; i < fastCount; i++) {
                futs[i] = pool.submit(() -> { runFast(mainJdbc, fastOk, fastTimedOut); });
            }
            for (Future<?> f : futs) f.get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            pool.shutdownNow();
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "slow path saturates shared pool, fast path times out");
        out.put("mainPoolSize", mainDs.getMaximumPoolSize());
        out.put("connectionTimeoutMs", mainDs.getConnectionTimeout());
        out.put("slowQueriesFired", slowCount);
        out.put("slowOk", slowOk.get());
        out.put("slowFailed", slowFailed.get());
        out.put("fastQueriesFired", fastCount);
        out.put("fastOk", fastOk.get());
        out.put("fastTimedOut", fastTimedOut.get());
        out.put("elapsedMs", elapsedMs);
        out.put("verdict",
            fastTimedOut.get() > 0
                ? "Cascade reproduced — " + fastTimedOut.get() + " fast requests timed out " +
                  "because the slow path ate every connection. In production this looks like " +
                  "your /healthz, your /metrics, your /login all failing at once, while one " +
                  "obscure /reports/export is the actual problem."
                : "No cascade — try increasing slowCount or slowMs.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 2. RETRY STORM.
    //
    // Same setup, but the fast path RETRIES on failure (up to 3 times,
    // ZERO backoff). Each retry borrows again, which competes with the
    // slow queries for the same exhausted pool. The total load on the
    // pool MULTIPLIES; the storm gets worse.
    //
    // This is the textbook "retries amplify outages" pattern.
    // ---------------------------------------------------------------------
    public Map<String, Object> retryStorm(int slowCount, long slowMs, int fastCount) {
        ExecutorService pool = Executors.newCachedThreadPool();
        AtomicInteger fastOk = new AtomicInteger();
        AtomicInteger fastTimedOut = new AtomicInteger();
        AtomicInteger totalAttempts = new AtomicInteger();
        AtomicInteger slowOk = new AtomicInteger();
        AtomicInteger slowFailed = new AtomicInteger();

        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < slowCount; i++) {
                pool.submit(() -> { runSlow(mainJdbc, slowMs, slowOk, slowFailed); });
            }
            Thread.sleep(50);
            Future<?>[] futs = new Future[fastCount];
            for (int i = 0; i < fastCount; i++) {
                futs[i] = pool.submit(() -> {
                    for (int a = 0; a < 3; a++) {
                        totalAttempts.incrementAndGet();
                        try { mainJdbc.queryForObject("select 1", Integer.class); fastOk.incrementAndGet(); return; }
                        catch (RuntimeException e) { /* retry immediately, no backoff */ }
                    }
                    fastTimedOut.incrementAndGet();
                });
            }
            for (Future<?> f : futs) f.get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            pool.shutdownNow();
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "retry-on-timeout amplifies the storm");
        out.put("slowQueriesFired", slowCount);
        out.put("fastQueriesFired", fastCount);
        out.put("totalFastAttempts", totalAttempts.get());
        out.put("amplification", totalAttempts.get() / (double) fastCount);
        out.put("fastOk", fastOk.get());
        out.put("fastTimedOut", fastTimedOut.get());
        out.put("elapsedMs", elapsedMs);
        out.put("verdict",
            "Total attempts = " + totalAttempts.get() + " for " + fastCount + " requests. " +
            "The retries didn't help — they MULTIPLIED the pool pressure by " +
            (totalAttempts.get() / (double) fastCount) + "x. Real fix: bulkhead the slow path " +
            "(see /pool/bulkhead), AND add exponential backoff + jitter, AND a circuit breaker.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 3. BULKHEAD.
    //
    // Slow code path runs on the SLOW pool (4 slots). Fast traffic runs on
    // the MAIN pool (10 slots). The two pools are physically separate —
    // saturating slow does NOT touch main. Fast requests stay fast.
    //
    // This is the bulkhead pattern at the connection-pool layer. The
    // semaphore equivalent (Resilience4j Bulkhead, or a manual
    // Semaphore.acquire/release) achieves the same isolation in pure code.
    // ---------------------------------------------------------------------
    public Map<String, Object> bulkhead(int slowCount, long slowMs, int fastCount) {
        ExecutorService pool = Executors.newCachedThreadPool();
        AtomicInteger fastOk = new AtomicInteger();
        AtomicInteger fastTimedOut = new AtomicInteger();
        AtomicInteger slowOk = new AtomicInteger();
        AtomicInteger slowFailed = new AtomicInteger();

        long t0 = System.nanoTime();
        try {
            // SLOW path → slowJdbc (its own pool).
            for (int i = 0; i < slowCount; i++) {
                pool.submit(() -> { runSlow(slowJdbc, slowMs, slowOk, slowFailed); });
            }
            Thread.sleep(50);
            // FAST path → mainJdbc (untouched by the slow path).
            Future<?>[] futs = new Future[fastCount];
            for (int i = 0; i < fastCount; i++) {
                futs[i] = pool.submit(() -> { runFast(mainJdbc, fastOk, fastTimedOut); });
            }
            for (Future<?> f : futs) f.get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            pool.shutdownNow();
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "slow path on its own pool, main pool stays healthy");
        out.put("mainPoolSize", mainDs.getMaximumPoolSize());
        out.put("slowPoolSize", slowDs.getMaximumPoolSize());
        out.put("slowQueriesFired", slowCount);
        out.put("slowOk", slowOk.get());
        out.put("slowFailed", slowFailed.get());
        out.put("fastQueriesFired", fastCount);
        out.put("fastOk", fastOk.get());
        out.put("fastTimedOut", fastTimedOut.get());
        out.put("elapsedMs", elapsedMs);
        out.put("verdict",
            fastOk.get() == fastCount && slowFailed.get() > 0
                ? "Bulkhead worked — slow pool saturated and shed load, main pool unaffected, " +
                  "all " + fastCount + " fast requests succeeded. The slow path can fail without " +
                  "killing the service. THIS is the production pattern."
                : fastOk.get() == fastCount
                    ? "Bulkhead worked — main pool unaffected, all fast requests succeeded."
                    : "Some fast requests still failed — investigate; bulkhead should fully isolate.");
        return out;
    }

    /** Snapshot stats on both pools. */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", poolSnapshot(mainDs));
        out.put("slow", poolSnapshot(slowDs));
        return out;
    }

    private Map<String, Object> poolSnapshot(HikariDataSource ds) {
        var mx = ds.getHikariPoolMXBean();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", ds.getPoolName());
        m.put("max", ds.getMaximumPoolSize());
        m.put("active", mx.getActiveConnections());
        m.put("idle", mx.getIdleConnections());
        m.put("waiting", mx.getThreadsAwaitingConnection());
        return m;
    }

    // ----- helpers -------------------------------------------------------

    /** A slow query: hold the connection for `holdMs`, then run a no-op. */
    private void runSlow(JdbcTemplate jdbc, long holdMs, AtomicInteger ok, AtomicInteger failed) {
        try {
            jdbc.execute("select pg_sleep(" + (holdMs / 1000.0) + ")");
            ok.incrementAndGet();
        } catch (RuntimeException e) {
            failed.incrementAndGet();
        }
    }

    /** A fast query — supposed to complete in ms unless the pool is starved. */
    private void runFast(JdbcTemplate jdbc, AtomicInteger ok, AtomicInteger timedOut) {
        try {
            jdbc.queryForObject("select 1", Integer.class);
            ok.incrementAndGet();
        } catch (RuntimeException e) {
            timedOut.incrementAndGet();
        }
    }
}
