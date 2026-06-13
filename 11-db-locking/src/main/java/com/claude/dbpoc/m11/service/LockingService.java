package com.claude.dbpoc.m11.service;

import com.claude.dbpoc.m11.domain.Account;
import com.claude.dbpoc.m11.domain.Job;
import com.claude.dbpoc.m11.repo.AccountRepository;
import com.claude.dbpoc.m11.repo.JobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Every locking primitive in this module is a method here.
 *
 * Each method runs a deterministic race: T1 takes a lock, the timing is
 * arranged so T2 attempts the conflicting operation while T1 still
 * holds the lock, and the JSON response reports what actually happened
 * — wait time, exception class, who blocked whom, etc.
 *
 * The four pessimistic-lock variants:
 *   1. forUpdate              — JPA @Lock(PESSIMISTIC_WRITE). Waits.
 *   2. skipLocked             — fan out N workers over a job queue.
 *   3. nowait                 — fail fast if the row is contended.
 *   4. tableLevel             — LOCK TABLE for whole-table coordination.
 *   5. observability          — query pg_locks to see who's waiting on whom.
 */
@Service
public class LockingService {

    private final AccountRepository accountRepo;
    private final JobRepository jobRepo;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager em;

    public LockingService(AccountRepository accountRepo, JobRepository jobRepo, TransactionTemplate tx) {
        this.accountRepo = accountRepo;
        this.jobRepo = jobRepo;
        this.tx = tx;
    }

    // =====================================================================
    // 1. Plain SELECT ... FOR UPDATE — the wait demo.
    //
    // T1 takes a row lock and holds it for 300ms. T2 tries the same FOR
    // UPDATE and BLOCKS inside Postgres until T1 commits. Reporting the
    // elapsed time on T2 proves the wait happened.
    //
    // Production note: this is the "default" pessimistic primitive. Use
    // it when you know there will be contention and you want callers to
    // queue rather than fail. Drawback: if T1 hangs (network I/O, slow
    // external API call) every waiter on the row is also hung.
    // =====================================================================
    public Map<String, Object> forUpdate(Long accountId) {
        CountDownLatch t1Locked = new CountDownLatch(1);
        long startNs = System.nanoTime();

        Concurrency.Pair<Long, Long> r = Concurrency.runBoth(
            // T1: take the lock, hold 300ms, commit. The 300ms is the
            // "I'm doing slow work while holding the lock" simulation.
            () -> tx.execute(status -> {
                long t = System.nanoTime();
                Account a = accountRepo.findByIdForUpdate(accountId).orElseThrow();
                a.setBalance(a.getBalance().add(new BigDecimal("1.00")));
                accountRepo.saveAndFlush(a);
                t1Locked.countDown();
                // Hold the lock so T2 has something to wait on.
                Concurrency.quiet(300);
                return (System.nanoTime() - t) / 1_000_000L;     // ms
            }),
            // T2: wait until T1 confirms it has the lock, then try to
            // take the same lock. This call SLEEPS inside Postgres
            // until T1 commits. We measure the elapsed time as the
            // proof of wait.
            () -> {
                Concurrency.await(t1Locked);
                return tx.execute(status -> {
                    long t = System.nanoTime();
                    Account a = accountRepo.findByIdForUpdate(accountId).orElseThrow();
                    a.setBalance(a.getBalance().add(new BigDecimal("1.00")));
                    accountRepo.saveAndFlush(a);
                    return (System.nanoTime() - t) / 1_000_000L;     // ms — should be ~300
                });
            },
            10
        );

        long total = (System.nanoTime() - startNs) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("primitive", "SELECT ... FOR UPDATE (plain)");
        out.put("t1ElapsedMs", r.first());
        out.put("t2ElapsedMs", r.second());
        out.put("totalElapsedMs", total);
        out.put("verdict",
            "T1 held the row lock for ~300ms. T2's FOR UPDATE blocked inside Postgres for " +
            r.second() + "ms until T1 committed. This is the expected wait behaviour — " +
            "use this primitive when callers should queue rather than fail.");
        return out;
    }

    // =====================================================================
    // 2. SELECT ... FOR UPDATE SKIP LOCKED — the queue-worker demo.
    //
    // The headline pattern of this module. N worker threads simultaneously
    // pull `perWorker` rows each from the job table. Because of SKIP
    // LOCKED, no two workers ever see the same row, AND nobody waits.
    //
    // Production note: this is the cheapest way to fan out a stream of
    // independent work across stateless workers. The DB is the
    // coordinator. No Redis, no Kafka, no Zookeeper — just SQL. Used
    // heavily by Sidekiq-pg, GoodJob, Faktory, etc.
    //
    // Catches you'll hit in production:
    //   - Long-running jobs hold the row lock for the whole tx. If your
    //     job takes 30s, you tie up a row+connection for 30s. Move the
    //     I/O OUT of the tx if you can.
    //   - Worker crash mid-job → Postgres releases the row lock on
    //     connection teardown and the row goes back to PENDING. The
    //     next worker re-claims it. Make your job logic IDEMPOTENT.
    //   - ORDER BY id keeps work FIFO. ORDER BY priority DESC, id ASC
    //     for priority queues. Without an order, Postgres is free to
    //     return rows in any order — fine for fanout, weird for FIFO.
    // =====================================================================
    public Map<String, Object> skipLocked(int workers, int perWorker) {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            // Gate all workers behind a single latch so they fire as close
            // to simultaneously as the OS allows. Without this, the first
            // worker would finish before the last one started and SKIP
            // LOCKED's contention behaviour wouldn't be visible.
            CountDownLatch fire = new CountDownLatch(1);

            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                final String workerName = "worker-" + i;
                futures.add(pool.submit(() -> {
                    Concurrency.await(fire);
                    return tx.execute(status -> {
                        List<Job> claimed = jobRepo.claimPending(perWorker);
                        Instant now = Instant.now();
                        List<Long> ids = new ArrayList<>(claimed.size());
                        for (Job j : claimed) {
                            j.setStatus(Job.Status.DONE);
                            j.setLockedBy(workerName);
                            j.setLockedAt(now);
                            ids.add(j.getId());
                        }
                        // saveAndFlush isn't strictly needed because we're
                        // in a managed entity and the tx commit will flush.
                        // We call it anyway so the UPDATEs are visible in
                        // p6spy logs in source order.
                        jobRepo.saveAllAndFlush(claimed);
                        return ids;
                    });
                }));
            }
            fire.countDown();

            // Collect each worker's claims and verify disjointness.
            Map<String, List<Long>> perWorkerIds = new LinkedHashMap<>();
            Set<Long> all = ConcurrentHashMap.newKeySet();
            int overlap = 0;
            for (int i = 0; i < workers; i++) {
                List<Long> ids = futures.get(i).get(15, TimeUnit.SECONDS);
                perWorkerIds.put("worker-" + i, ids);
                for (Long id : ids) {
                    if (!all.add(id)) overlap++;
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("primitive", "SELECT ... FOR UPDATE SKIP LOCKED");
            out.put("workers", workers);
            out.put("perWorker", perWorker);
            out.put("perWorkerIds", perWorkerIds);
            out.put("totalDistinct", all.size());
            out.put("overlapCount", overlap);
            out.put("verdict",
                overlap == 0
                    ? "Zero overlap across " + workers + " workers. SKIP LOCKED produced " +
                      "disjoint, contention-free fanout — exactly the queue-worker pattern."
                    : "OVERLAP DETECTED (" + overlap + " collisions). Something is wrong — " +
                      "SKIP LOCKED should never produce duplicates across concurrent claimers.");
            return out;
        } catch (Exception e) {
            throw new RuntimeException("skipLocked demo failed: " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
    }

    // =====================================================================
    // 3. SELECT ... FOR UPDATE NOWAIT — fail-fast demo.
    //
    // T1 holds the row lock. T2 issues FOR UPDATE NOWAIT and IMMEDIATELY
    // fails with PSQLException whose SQLState is 55P03 (lock_not_available).
    //
    // The proof is T2's elapsed time: under 50ms. A plain FOR UPDATE
    // here would have waited the full 300ms T1 held the lock.
    //
    // Production note: use this when you'd rather return a fast 409 to
    // the client than tie up a request thread waiting for a busy row.
    // Common in REST endpoints that perform money transfers — better to
    // fail fast and let the client retry than to pile up waiters.
    // =====================================================================
    public Map<String, Object> nowait(Long accountId) {
        CountDownLatch t1Locked = new CountDownLatch(1);
        CountDownLatch t2Done = new CountDownLatch(1);

        Concurrency.Pair<Long, Map<String, Object>> r = Concurrency.runBoth(
            // T1: take the lock and hold for 300ms.
            () -> tx.execute(status -> {
                long t = System.nanoTime();
                Account a = accountRepo.findByIdForUpdate(accountId).orElseThrow();
                a.setBalance(a.getBalance().add(new BigDecimal("1.00")));
                accountRepo.saveAndFlush(a);
                t1Locked.countDown();
                // Wait for T2 to finish before releasing — guarantees
                // T2's attempt happened during the hold.
                Concurrency.await(t2Done);
                Concurrency.quiet(50);     // small tail so T1 visibly outlasts T2
                return (System.nanoTime() - t) / 1_000_000L;
            }),
            () -> {
                Concurrency.await(t1Locked);
                Map<String, Object> out = new LinkedHashMap<>();
                long start = System.nanoTime();
                try {
                    tx.execute(status -> {
                        Account a = accountRepo.findByIdForUpdateNowait(accountId).orElseThrow();
                        // Should never reach here while T1 holds the lock.
                        a.setBalance(a.getBalance().add(new BigDecimal("1.00")));
                        accountRepo.saveAndFlush(a);
                        return null;
                    });
                    long elapsed = (System.nanoTime() - start) / 1_000_000L;
                    out.put("elapsedMs", elapsed);
                    out.put("outcome", "ACQUIRED — unexpected, T1 must not have held the lock");
                } catch (RuntimeException ex) {
                    long elapsed = (System.nanoTime() - start) / 1_000_000L;
                    out.put("elapsedMs", elapsed);
                    out.put("outcome", "FAILED-FAST");
                    out.put("exception", rootCauseClass(ex));
                    out.put("message", rootCauseMessage(ex));
                    out.put("isLockNotAvailable", containsAny(ex, "55P03", "could not obtain lock", "lock_not_available"));
                } finally {
                    t2Done.countDown();
                }
                return out;
            },
            10
        );

        Map<String, Object> t2 = r.second();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("primitive", "SELECT ... FOR UPDATE NOWAIT");
        out.put("t1HoldMs", r.first());
        out.put("t2", t2);
        long t2Elapsed = ((Number) t2.get("elapsedMs")).longValue();
        out.put("verdict",
            t2Elapsed < 50
                ? "T2 failed in " + t2Elapsed + "ms — proof of no wait. T1 held the lock for ~300ms; " +
                  "a plain FOR UPDATE here would have queued that long. NOWAIT means: don't queue, " +
                  "raise SQLSTATE 55P03 immediately."
                : "T2 took " + t2Elapsed + "ms — this looks like a wait, not a fail-fast. Check " +
                  "that the NOWAIT clause actually reached the wire (see p6spy log).");
        return out;
    }

    // =====================================================================
    // 4. Table-level LOCK TABLE ... IN EXCLUSIVE MODE.
    //
    // Row-level FOR UPDATE only blocks other writers on the SAME ROW.
    // A whole-table lock blocks every read AND write of the table for
    // the duration of the holding transaction.
    //
    // Production note: almost never the right choice for online traffic.
    // The legitimate use cases are:
    //   - One-off DDL-adjacent maintenance ("nobody touch this table
    //     while I rebuild this index by hand").
    //   - Coordinating a singleton background job that absolutely must
    //     not race with another instance.
    //
    // EXCLUSIVE MODE blocks SELECTs too. ACCESS EXCLUSIVE is even
    // stronger (it's what DROP TABLE and many ALTER TABLEs take).
    // SHARE / ROW EXCLUSIVE / etc are weaker. See pg docs § Explicit
    // Locking.
    // =====================================================================
    public Map<String, Object> tableLevel() {
        CountDownLatch tableLocked = new CountDownLatch(1);
        CountDownLatch tableReleased = new CountDownLatch(1);

        Concurrency.Pair<Long, Map<String, Object>> r = Concurrency.runBoth(
            // T1: take an EXCLUSIVE mode lock on the table for 200ms.
            // The native LOCK TABLE statement is the only portable way
            // to do this — JPA has no equivalent.
            () -> tx.execute(status -> {
                long t = System.nanoTime();
                em.createNativeQuery("LOCK TABLE account IN EXCLUSIVE MODE").executeUpdate();
                tableLocked.countDown();
                Concurrency.quiet(200);
                long held = (System.nanoTime() - t) / 1_000_000L;
                tableReleased.countDown();
                return held;
            }),
            () -> {
                Concurrency.await(tableLocked);
                long start = System.nanoTime();
                // A SELECT that would normally be free under MVCC. We're
                // about to find out whether EXCLUSIVE mode blocks plain
                // SELECTs (it does — that's the point of EXCLUSIVE vs
                // SHARE ROW EXCLUSIVE).
                Long count = tx.execute(status -> em.createNativeQuery(
                        "select count(*) from account").getSingleResult() instanceof Number n
                        ? n.longValue() : 0L);
                long elapsed = (System.nanoTime() - start) / 1_000_000L;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("selectElapsedMs", elapsed);
                out.put("count", count);
                out.put("blocked", elapsed >= 150);     // ~200ms hold; 150ms threshold for jitter
                return out;
            },
            10
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("primitive", "LOCK TABLE account IN EXCLUSIVE MODE");
        out.put("t1HoldMs", r.first());
        out.put("t2", r.second());
        out.put("verdict",
            ((Boolean) r.second().get("blocked"))
                ? "T2's plain SELECT was blocked by T1's EXCLUSIVE table lock for ~" +
                  r.second().get("selectElapsedMs") + "ms. EXCLUSIVE MODE prevents ALL writers AND " +
                  "all reads — it is too heavy for online traffic. Use only for DDL-adjacent maintenance."
                : "T2's SELECT was NOT blocked — check that EXCLUSIVE MODE was actually used (not " +
                  "SHARE / ROW EXCLUSIVE which permit reads).");
        return out;
    }

    // =====================================================================
    // 5. pg_locks observability — "who is locking whom".
    //
    // While T1 holds a FOR UPDATE on the account, a SEPARATE connection
    // (T3) queries pg_locks + pg_stat_activity. The result is the
    // canonical wait-for graph you ship to your monitoring system.
    //
    // Production note: pg_blocking_pids() (Postgres 9.6+) is the
    // higher-level helper — given a PID, returns the PIDs blocking it.
    // We use it here AND join pg_locks for the full lock state.
    // =====================================================================
    @SuppressWarnings("unchecked")
    public Map<String, Object> observability(Long accountId) {
        CountDownLatch t1Locked = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);

        Concurrency.Pair<Long, List<Map<String, Object>>> r = Concurrency.runBoth(
            // T1: take the row lock, hold 1s. Plenty of time for the
            // observer to query pg_locks.
            () -> tx.execute(status -> {
                long t = System.nanoTime();
                Account a = accountRepo.findByIdForUpdate(accountId).orElseThrow();
                a.setBalance(a.getBalance().add(new BigDecimal("1.00")));
                accountRepo.saveAndFlush(a);
                t1Locked.countDown();
                // Hold until the observer says it's done snapshotting.
                Concurrency.await(observed);
                Concurrency.quiet(50);
                return (System.nanoTime() - t) / 1_000_000L;
            }),
            () -> {
                Concurrency.await(t1Locked);
                // Give Postgres a beat to register the lock in pg_locks
                // (the catalog write happens at LWLock release, which
                // is a few microseconds after the FOR UPDATE returns).
                Concurrency.quiet(50);
                List<Map<String, Object>> rows = tx.execute(status -> {
                    // The canonical "who is locking whom + on what" query.
                    // - pg_stat_activity gives us the SQL each backend is
                    //   running and how long it's been there.
                    // - pg_locks tells us which lock objects each backend
                    //   holds, and at what mode (RowExclusive, etc).
                    // - We filter to locks on our 'account' relation so
                    //   the demo output isn't drowned by Postgres'
                    //   internal catalog locks.
                    List<Object[]> raw = em.createNativeQuery(
                        "select " +
                        "  a.pid, " +
                        "  a.application_name, " +
                        "  a.state, " +
                        "  a.wait_event_type, " +
                        "  a.wait_event, " +
                        "  l.locktype, " +
                        "  l.mode, " +
                        "  l.granted, " +
                        "  l.relation::regclass::text as relation, " +
                        "  left(a.query, 120) as query " +
                        "from pg_locks l " +
                        "join pg_stat_activity a on a.pid = l.pid " +
                        "where l.relation = 'account'::regclass " +
                        "   or l.locktype = 'transactionid' " +
                        "   or l.locktype = 'tuple' " +
                        "order by a.pid, l.granted desc")
                        .getResultList();
                    List<Map<String, Object>> out = new ArrayList<>(raw.size());
                    for (Object[] row : raw) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("pid", row[0]);
                        m.put("application", row[1]);
                        m.put("state", row[2]);
                        m.put("waitEventType", row[3]);
                        m.put("waitEvent", row[4]);
                        m.put("locktype", row[5]);
                        m.put("mode", row[6]);
                        m.put("granted", row[7]);
                        m.put("relation", row[8]);
                        m.put("query", row[9]);
                        out.add(m);
                    }
                    return out;
                });
                observed.countDown();
                return rows;
            },
            10
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("primitive", "pg_locks + pg_stat_activity (observability)");
        out.put("t1HoldMs", r.first());
        out.put("locks", r.second() == null ? Collections.emptyList() : r.second());
        out.put("note",
            "Each row is a held-or-requested lock. granted=true means it's the holder; " +
            "granted=false would mean a waiter. The canonical 'who is blocking whom' query " +
            "extends this with pg_blocking_pids() — see README.");
        return out;
    }

    // ---- helpers -------------------------------------------------------

    private static String rootCauseClass(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getName();
    }

    private static String rootCauseMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage();
    }

    private static boolean containsAny(Throwable t, String... needles) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null) {
                for (String n : needles) {
                    if (msg.contains(n)) return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }
}
