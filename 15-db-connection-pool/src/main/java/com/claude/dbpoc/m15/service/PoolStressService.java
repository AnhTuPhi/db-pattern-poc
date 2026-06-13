package com.claude.dbpoc.m15.service;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives synthetic load against the autoconfigured HikariCP pool so we can
 * SHOW what each knob does instead of arguing about it.
 *
 * Why a dedicated service:
 *   - We need a real ExecutorService (not Spring's @Async) so the test is
 *     deterministic and self-contained: we know exactly how many threads
 *     we spawn and we await them all.
 *   - We need to differentiate "got a connection immediately" from "waited
 *     in the borrow queue" from "timed out at connection-timeout". The
 *     pool MXBean and the SQLState on the exception are the signals.
 *
 * Implementation notes:
 *   - We obtain the DataSource via Spring and cast to HikariDataSource so
 *     we can read getHikariPoolMXBean() — there is no portable JDBC API
 *     for "how many idle connections are in the pool" and Hikari's MXBean
 *     is the canonical answer.
 *   - try-with-resources is mandatory; this module models the RIGHT shape.
 *     Module 16 demonstrates what happens when you don't.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoolStressService {

    private final DataSource dataSource;

    /**
     * Snapshot of the pool's live counters + the configured knobs.
     *
     * The MXBean fields:
     *   - activeConnections        — currently checked out (in use)
     *   - idleConnections          — sitting in the pool ready to hand out
     *   - totalConnections         — active + idle (= current pool size)
     *   - threadsAwaitingConnection — borrowers stuck in getConnection()
     *
     * The configuration fields below are static once the pool is built,
     * but we surface them so callers don't have to grep application.yml.
     */
    public Map<String, Object> inspect() {
        HikariDataSource hds = unwrap();
        HikariPoolMXBean mx = hds.getHikariPoolMXBean();

        Map<String, Object> live = new LinkedHashMap<>();
        live.put("activeConnections", mx.getActiveConnections());
        live.put("idleConnections", mx.getIdleConnections());
        live.put("totalConnections", mx.getTotalConnections());
        live.put("threadsAwaitingConnection", mx.getThreadsAwaitingConnection());

        Map<String, Object> knobs = new LinkedHashMap<>();
        knobs.put("poolName", hds.getPoolName());
        knobs.put("maximumPoolSize", hds.getMaximumPoolSize());
        knobs.put("minimumIdle", hds.getMinimumIdle());
        knobs.put("connectionTimeoutMs", hds.getConnectionTimeout());
        knobs.put("idleTimeoutMs", hds.getIdleTimeout());
        knobs.put("maxLifetimeMs", hds.getMaxLifetime());
        knobs.put("validationTimeoutMs", hds.getValidationTimeout());
        knobs.put("leakDetectionThresholdMs", hds.getLeakDetectionThreshold());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("live", live);
        out.put("configured", knobs);
        return out;
    }

    /**
     * Acquire `threads` connections in parallel, hold each for `holdMs`,
     * then release. Reports the three outcomes that matter:
     *
     *   acquiredImmediately — getConnection() returned without queueing
     *   waited              — getConnection() returned but only after
     *                         queueing (we can't read the queue time
     *                         directly here without instrumentation, but
     *                         a non-zero pool delta during the run tells
     *                         us threads piled up)
     *   timedOut            — getConnection() threw
     *                         SQLTransientConnectionException — the
     *                         connection-timeout kicked in.
     *
     * Why the timeout is the headline: that is the failure mode that
     * cascades. Under sustained overload, every borrower hits this and
     * the service brownouts. Pool sizing has to keep this number at 0.
     *
     * The hold uses pg_sleep on the server-side rather than Thread.sleep
     * on the client so we are exercising a real Postgres backend, the
     * way a slow query would.
     */
    public Map<String, Object> stress(int threads, long holdMs) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go    = new CountDownLatch(1);

        AtomicInteger ok      = new AtomicInteger();
        AtomicInteger timeout = new AtomicInteger();
        AtomicInteger other   = new AtomicInteger();

        long started = System.nanoTime();
        List<Future<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int id = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();

                // try-with-resources is the WHOLE POINT of this module's
                // shape — release is automatic at close(). Module 16
                // shows what happens when you skip it.
                try (Connection c = dataSource.getConnection();
                     var st = c.createStatement()) {
                    // pg_sleep(holdMs/1000.0) — holds the connection on
                    // the server. This is the canonical "slow query" stand-in.
                    st.execute("SELECT pg_sleep(%s)".formatted(holdMs / 1000.0));
                    ok.incrementAndGet();
                } catch (SQLTransientConnectionException ste) {
                    // The exception Hikari throws when connection-timeout
                    // elapses with no free connection. This is the
                    // signal the pool is too small for the offered load.
                    log.info("worker {} timed out at the borrow boundary: {}",
                            id, ste.getMessage());
                    timeout.incrementAndGet();
                } catch (Exception e) {
                    log.warn("worker {} failed: {}", id, e.toString());
                    other.incrementAndGet();
                }
                return null;
            }));
        }

        try {
            // Wait until every worker is parked at `go` before releasing
            // them. This pushes the contention spike into a tight window
            // and makes the outcome deterministic.
            ready.await();
            go.countDown();
            for (Future<Void> f : futures) f.get();
        } catch (Exception e) {
            log.error("stress driver crashed", e);
        } finally {
            pool.shutdownNow();
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadsRequested", threads);
        out.put("holdMs", holdMs);
        out.put("acquiredAndCompleted", ok.get());
        out.put("timedOutAtBorrow", timeout.get());
        out.put("otherErrors", other.get());
        out.put("elapsedMs", elapsedMs);
        out.put("postRunPoolState", inspect().get("live"));
        out.put("interpretation", interpret(threads, ok.get(), timeout.get(), holdMs));
        return out;
    }

    /**
     * Three samples of the pool's totalConnections over a 12s window —
     * enough to see Hikari open new connections to satisfy minimum-idle
     * after we briefly check several out. To actually witness maxLifetime
     * rotation in a unit test you would need to set max-lifetime below
     * 30s (Hikari's lower bound is 30s when not zero), so this endpoint
     * documents the mechanism and reports the configured value rather
     * than waiting 5 minutes for the real thing.
     */
    public Map<String, Object> lifetimeRotation() throws InterruptedException {
        HikariDataSource hds = unwrap();
        HikariPoolMXBean mx = hds.getHikariPoolMXBean();

        List<Map<String, Object>> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("atMs", i * 2000);
            s.put("active", mx.getActiveConnections());
            s.put("idle", mx.getIdleConnections());
            s.put("total", mx.getTotalConnections());
            samples.add(s);
            Thread.sleep(2000);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxLifetimeMs", hds.getMaxLifetime());
        out.put("note",
                "Rotation fires when a connection's age exceeds maxLifetime. "
              + "Hikari retires it on return, not mid-flight, so a long-held "
              + "connection past maxLifetime is still serviced; the next "
              + "borrower gets a fresh one. To observe rotation in seconds, "
              + "set max-lifetime to 30000 (Hikari's lower bound) and run "
              + "/pool/stress in a loop.");
        out.put("samples", samples);
        return out;
    }

    /**
     * The Brett Wooldridge sizing formula and an opinion about the result.
     *
     *   connections = ((core_count * 2) + effective_spindle_count) * workloadFactor
     *
     * Wooldridge originally proposed it without the workloadFactor as a
     * STARTING POINT; we expose the multiplier so the caller can see how
     * sensitive the answer is. The opinion text reflects the empirical
     * finding that pools larger than the recommendation typically REDUCE
     * throughput under sustained load because they pile work on Postgres'
     * fixed number of backends.
     */
    public Map<String, Object> sizingMath(int cores, int spindles, double workloadFactor) {
        double base = (cores * 2.0) + spindles;
        double recommended = base * workloadFactor;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formula", "((cores * 2) + spindles) * workloadFactor");
        out.put("inputs", Map.of(
                "cores", cores,
                "spindles", spindles,
                "workloadFactor", workloadFactor));
        out.put("baseRecommendation", Math.round(base));
        out.put("withWorkloadFactor", Math.round(recommended));

        String opinion;
        if (recommended < 8) {
            opinion = "Tiny pool. Fine for a low-QPS internal service; "
                    + "double-check that your worker threads aren't blocking "
                    + "on the pool more than they are doing real work.";
        } else if (recommended <= 25) {
            opinion = "Standard. This is the sweet spot for an OLTP web "
                    + "service. Verify with a latency curve: increase pool "
                    + "size until p99 starts climbing, then back off one step.";
        } else if (recommended <= 50) {
            opinion = "Large. Make sure Postgres' max_connections accommodates "
                    + "ALL clients summed — this is the budget you share with "
                    + "every other replica of this service. Consider pgbouncer.";
        } else {
            opinion = "Too large. Postgres backends are expensive (memory, "
                    + "context-switch) and a single backend serialises work "
                    + "anyway. You almost certainly want pgbouncer in front "
                    + "and a smaller per-app pool.";
        }
        out.put("opinion", opinion);
        out.put("note",
                "This formula is a starting point, not the answer. Measure the "
              + "p95/p99 latency curve under your actual workload and pick "
              + "the knee — that's the only correct way to size.");
        return out;
    }

    /**
     * Cast helper. Hikari is the autoconfigured pool, but in test contexts
     * it could be wrapped (proxies, decorators). We fail loud here rather
     * than silently degrading, because the whole module is meaningless if
     * we can't reach the MXBean.
     */
    private HikariDataSource unwrap() {
        if (dataSource instanceof HikariDataSource h) return h;
        try {
            // Spring Boot sometimes wraps DataSource. Try unwrap.
            return dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DataSource is not a HikariDataSource — this module relies "
                  + "on HikariPoolMXBean. Got: " + dataSource.getClass(), e);
        }
    }

    private String interpret(int threads, int ok, int timedOut, long holdMs) {
        if (timedOut == 0 && ok == threads) {
            return "Pool absorbed the load with zero borrow timeouts. "
                 + "Either the pool is sized comfortably for this concurrency, "
                 + "or holdMs is too short to cause contention.";
        }
        if (timedOut > 0) {
            return ("%d of %d borrows timed out at connection-timeout. The pool "
                  + "is smaller than the concurrent demand for the duration of "
                  + "the hold (%d ms). Either raise maximum-pool-size, shorten "
                  + "the held work, or bulkhead this path so it can't starve "
                  + "everyone else (see module 17).").formatted(timedOut, threads, holdMs);
        }
        return "Mixed outcome — check logs for SQLState and consider whether "
             + "validation-timeout or network jitter is in play.";
    }
}
