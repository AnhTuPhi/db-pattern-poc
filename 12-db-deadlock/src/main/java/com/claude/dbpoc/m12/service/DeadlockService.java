package com.claude.dbpoc.m12.service;

import com.claude.dbpoc.m12.domain.Account;
import com.claude.dbpoc.m12.repo.AccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Four endpoints, four lessons:
 *
 *   1. reproduce       — fire the textbook A→B / B→A race; expect 40P01.
 *   2. graph           — snapshot pg_locks + pg_blocking_pids during the race.
 *   3. lockOrdering    — same workload, canonical id order, no deadlock.
 *   4. retryAtBoundary — leave the buggy ordering, retry on 40P01.
 *
 * Why lock-ordering and not "retry harder" is the right fix:
 *   - Retries waste CPU and saturate the wait-for graph more.
 *   - The cycle is the bug. Removing the cycle removes the bug.
 *   - Lock-ordering is a STRUCTURAL property of the code, not a runtime
 *     mitigation. You can review it at the function boundary.
 */
@Service
public class DeadlockService {

    private final AccountRepository accountRepo;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager em;

    public DeadlockService(AccountRepository accountRepo, TransactionTemplate tx) {
        this.accountRepo = accountRepo;
        this.tx = tx;
    }

    // ---------------------------------------------------------------------
    // 1. REPRODUCE the deadlock.
    //
    // T1: lock A, hold 150ms, then try to lock B → waits on T2.
    // T2: lock B, hold 150ms, then try to lock A → waits on T1.
    //
    // Postgres' deadlock_timeout (default 1s) is the LATENCY of detection,
    // not the window after which it fires — the engine periodically scans
    // the wait-for graph; if it finds a cycle, ONE of the waiters is
    // aborted with SQLSTATE 40P01 (deadlock_detected).
    //
    // The aborted transaction's exception chain wraps a PSQLException with
    // SQLState 40P01 — that's how we identify "this was a deadlock" vs. any
    // other lock-related failure. Some Spring layers translate this to
    // CannotAcquireLockException; we sniff both.
    // ---------------------------------------------------------------------
    public Map<String, Object> reproduce(Long idA, Long idB) {
        CountDownLatch t1HasA = new CountDownLatch(1);
        CountDownLatch t2HasB = new CountDownLatch(1);

        Throwable[] errs = new Throwable[2];

        long t0 = System.nanoTime();
        Concurrency.Pair<Boolean, Boolean> r = Concurrency.runBoth(
            () -> safeRun(0, errs, () -> tx.execute(status -> {
                Account a = accountRepo.findByIdForUpdate(idA).orElseThrow();
                t1HasA.countDown();
                // Wait for T2 to confirm it holds B, so the cycle is guaranteed.
                Concurrency.await(t2HasB);
                // This is the step that will deadlock — T2 has B.
                Account b = accountRepo.findByIdForUpdate(idB).orElseThrow();
                a.setBalance(a.getBalance().subtract(BigDecimal.ONE));
                b.setBalance(b.getBalance().add(BigDecimal.ONE));
                accountRepo.saveAndFlush(a);
                accountRepo.saveAndFlush(b);
                return true;
            })),
            () -> safeRun(1, errs, () -> tx.execute(status -> {
                // T2 waits for T1 to confirm it holds A. Otherwise T2
                // might rush ahead and take both locks before T1 catches up.
                Concurrency.await(t1HasA);
                Account b = accountRepo.findByIdForUpdate(idB).orElseThrow();
                t2HasB.countDown();
                // The step that completes the cycle — T1 has A.
                Account a = accountRepo.findByIdForUpdate(idA).orElseThrow();
                b.setBalance(b.getBalance().subtract(BigDecimal.ONE));
                a.setBalance(a.getBalance().add(BigDecimal.ONE));
                accountRepo.saveAndFlush(b);
                accountRepo.saveAndFlush(a);
                return true;
            })),
            15
        );
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        int losers = 0;
        String firstErr = errs[0] == null ? null : errs[0].getClass().getSimpleName();
        String secondErr = errs[1] == null ? null : errs[1].getClass().getSimpleName();
        boolean firstWas40P01 = errs[0] != null && containsAny(errs[0], "40P01", "deadlock");
        boolean secondWas40P01 = errs[1] != null && containsAny(errs[1], "40P01", "deadlock");
        if (firstWas40P01) losers++;
        if (secondWas40P01) losers++;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "T1: lock A→B  /  T2: lock B→A");
        out.put("idA", idA);
        out.put("idB", idB);
        out.put("t1Outcome", r.first() != null && r.first() ? "committed" : "aborted: " + firstErr);
        out.put("t2Outcome", r.second() != null && r.second() ? "committed" : "aborted: " + secondErr);
        out.put("t1Was40P01", firstWas40P01);
        out.put("t2Was40P01", secondWas40P01);
        out.put("elapsedMs", elapsedMs);
        out.put("verdict",
            losers == 1
                ? "Deadlock fired. Postgres' detector aborted ONE transaction with 40P01; " +
                  "the other committed. Total elapsed " + elapsedMs + "ms (≈ deadlock_timeout). " +
                  "Note: 40P01 is the SIGNAL of the bug, not the bug. The bug is the lock-ordering " +
                  "asymmetry — see /deadlock/lock-ordering for the structural fix."
                : losers == 0
                    ? "No deadlock observed. Either the race didn't line up (re-run) or " +
                      "deadlock_timeout was set to 0 and detection was disabled."
                    : "Both transactions reported 40P01 — unexpected. Investigate the test harness.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 2. The wait-for graph snapshot.
    //
    // Spawn the deadlock scenario, but instead of waiting for the abort,
    // spin a SEPARATE connection that queries pg_locks + pg_blocking_pids
    // mid-flight. Result is the canonical "T1 (pid X) is blocked by T2
    // (pid Y), and T2 is blocked by T1" cycle.
    //
    // In production, this is the query you paste into psql at 3am when
    // your application logs say "deadlock detected" and you need to find
    // out which two code paths are doing it.
    // ---------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Map<String, Object> graph(Long idA, Long idB) {
        CountDownLatch t1HasA = new CountDownLatch(1);
        CountDownLatch t2HasB = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        Throwable[] errs = new Throwable[2];

        // We use a 3-thread pool: T1, T2, and the observer. The observer
        // takes its own tx (so its connection is independent of T1/T2).
        AtomicInteger _ = new AtomicInteger();
        final List<Map<String, Object>>[] snapshot = new List[1];

        long t0 = System.nanoTime();
        Concurrency.Pair<Object, Object> r = Concurrency.runBoth(
            () -> {
                // T1 and the observer share thread A — first do T1, then observe.
                safeRun(0, errs, () -> tx.execute(status -> {
                    Account a = accountRepo.findByIdForUpdate(idA).orElseThrow();
                    t1HasA.countDown();
                    Concurrency.await(t2HasB);
                    Account b = accountRepo.findByIdForUpdate(idB).orElseThrow();
                    a.setBalance(a.getBalance().subtract(BigDecimal.ONE));
                    b.setBalance(b.getBalance().add(BigDecimal.ONE));
                    accountRepo.saveAndFlush(a);
                    accountRepo.saveAndFlush(b);
                    return true;
                }));
                return null;
            },
            () -> {
                Concurrency.await(t1HasA);
                safeRun(1, errs, () -> tx.execute(status -> {
                    Account b = accountRepo.findByIdForUpdate(idB).orElseThrow();
                    t2HasB.countDown();
                    // Snapshot the graph BEFORE we attempt the deadlocking lock.
                    Concurrency.quiet(50);                  // give T1 a beat to attempt B
                    snapshot[0] = lockGraph();
                    observed.countDown();
                    // Now actually deadlock so the cycle ends and both threads finish.
                    Account a = accountRepo.findByIdForUpdate(idA).orElseThrow();
                    b.setBalance(b.getBalance().subtract(BigDecimal.ONE));
                    a.setBalance(a.getBalance().add(BigDecimal.ONE));
                    accountRepo.saveAndFlush(b);
                    accountRepo.saveAndFlush(a);
                    return true;
                }));
                return null;
            },
            20
        );
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "wait-for graph during A→B / B→A race");
        out.put("idA", idA);
        out.put("idB", idB);
        out.put("elapsedMs", elapsedMs);
        out.put("lockGraph", snapshot[0] == null ? List.of() : snapshot[0]);
        out.put("note",
            "Each row is one held-or-requested lock. granted=false rows are waiters. " +
            "blockedBy lists the PIDs whose locks the waiter is queued behind. A cycle in " +
            "blockedBy between two PIDs IS the deadlock. The 'query' column is the SQL the " +
            "backend is currently executing — useful for tracing back to the code path.");
        return out;
    }

    /** The canonical "who's blocking whom" snapshot — runs on its own short-lived tx. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lockGraph() {
        return tx.execute(status -> {
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
                "  pg_blocking_pids(a.pid) as blocked_by, " +
                "  left(a.query, 120) as query " +
                "from pg_locks l " +
                "join pg_stat_activity a on a.pid = l.pid " +
                "where (l.relation = 'account'::regclass " +
                "   or l.locktype in ('transactionid','tuple')) " +
                "  and a.pid <> pg_backend_pid() " +
                "order by a.pid, l.granted desc")
                .getResultList();
            List<Map<String, Object>> rows = new ArrayList<>(raw.size());
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
                m.put("blockedBy", row[9]);
                m.put("query", row[10]);
                rows.add(m);
            }
            return rows;
        });
    }

    // ---------------------------------------------------------------------
    // 3. The FIX: canonical lock-ordering.
    //
    // Same workload, but both transactions ALWAYS lock the lower id first.
    // The cycle is structurally impossible: both T1 and T2 try for the
    // same row first; one wins, the other waits, both eventually commit.
    //
    // The invariant in code: any time you take >1 lock in one tx, sort
    // the keys before acquiring. This is reviewable at the function
    // boundary — you don't need to think about all callers.
    // ---------------------------------------------------------------------
    public Map<String, Object> lockOrdering(Long idA, Long idB) {
        long t0 = System.nanoTime();
        Throwable[] errs = new Throwable[2];

        Concurrency.Pair<Boolean, Boolean> r = Concurrency.runBoth(
            () -> safeRun(0, errs, () -> tx.execute(status -> transferCanonical(idA, idB, BigDecimal.ONE))),
            () -> safeRun(1, errs, () -> tx.execute(status -> transferCanonical(idB, idA, BigDecimal.ONE))),
            10
        );

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        boolean any40P01 = (errs[0] != null && containsAny(errs[0], "40P01"))
                       || (errs[1] != null && containsAny(errs[1], "40P01"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "canonical lock-ordering: lock min(id) first");
        out.put("idA", idA);
        out.put("idB", idB);
        out.put("t1Committed", r.first() != null && r.first());
        out.put("t2Committed", r.second() != null && r.second());
        out.put("anyDeadlock", any40P01);
        out.put("elapsedMs", elapsedMs);
        out.put("verdict",
            !any40P01 && r.first() && r.second()
                ? "Both transactions committed with no deadlock. The cycle was removed " +
                  "STRUCTURALLY — neither transaction can wait on the other because both " +
                  "always take the lower id first. This is the right fix. Retry loops " +
                  "(see /deadlock/retry) are a fallback for the cases you can't refactor."
                : "Unexpected deadlock under canonical ordering — check that both methods " +
                  "really do sort the ids before findByIdForUpdate.");
        return out;
    }

    /**
     * Transfer `amount` from `from` to `to`, but ALWAYS lock the lower id
     * first. This is the entire fix. The business direction (from/to) is
     * decoupled from the lock acquisition order.
     */
    private boolean transferCanonical(Long from, Long to, BigDecimal amount) {
        Long firstLock = Math.min(from, to);
        Long secondLock = Math.max(from, to);
        Account a1 = accountRepo.findByIdForUpdate(firstLock).orElseThrow();
        Concurrency.quiet(80);                     // widen the race window
        Account a2 = accountRepo.findByIdForUpdate(secondLock).orElseThrow();
        Account src = from.equals(a1.getId()) ? a1 : a2;
        Account dst = to.equals(a1.getId()) ? a1 : a2;
        src.setBalance(src.getBalance().subtract(amount));
        dst.setBalance(dst.getBalance().add(amount));
        accountRepo.saveAndFlush(a1);
        accountRepo.saveAndFlush(a2);
        return true;
    }

    // ---------------------------------------------------------------------
    // 4. Retry at the transaction boundary.
    //
    // Use this when you CANNOT fix the lock-ordering (e.g. you don't own
    // the inner code, or the locks aren't pure row-level). It's NOT a
    // substitute for the structural fix — it just absorbs the residual
    // case where two callers happen to hit a path that wasn't refactored.
    //
    // Note: ALWAYS bound the retry budget. Unbounded retries under
    // sustained contention look like a DoS to monitoring.
    // ---------------------------------------------------------------------
    public Map<String, Object> retryAtBoundary(Long idA, Long idB) {
        AtomicInteger t1Retries = new AtomicInteger();
        AtomicInteger t2Retries = new AtomicInteger();
        long t0 = System.nanoTime();

        // Use the BUGGY ordering on purpose — that's what we're absorbing.
        Concurrency.Pair<Boolean, Boolean> r = Concurrency.runBoth(
            () -> retryingTransfer(idA, idB, t1Retries),
            () -> retryingTransfer(idB, idA, t2Retries),
            20
        );
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "buggy A→B / B→A ordering, with retry-on-40P01 at the boundary");
        out.put("t1Committed", r.first());
        out.put("t2Committed", r.second());
        out.put("t1Retries", t1Retries.get());
        out.put("t2Retries", t2Retries.get());
        out.put("elapsedMs", elapsedMs);
        out.put("verdict",
            "Retry loop absorbed the deadlock. " +
            (t1Retries.get() + t2Retries.get()) + " total retries observed. " +
            "Use this ONLY when you can't fix the ordering structurally — under sustained " +
            "contention the retry storm wastes CPU and saturates the wait-for graph.");
        return out;
    }

    private Boolean retryingTransfer(Long from, Long to, AtomicInteger retries) {
        int max = 5;
        for (int attempt = 0; attempt < max; attempt++) {
            try {
                return tx.execute(status -> {
                    // BUGGY: locks in business direction, not canonical order.
                    Account src = accountRepo.findByIdForUpdate(from).orElseThrow();
                    Concurrency.quiet(80);
                    Account dst = accountRepo.findByIdForUpdate(to).orElseThrow();
                    src.setBalance(src.getBalance().subtract(BigDecimal.ONE));
                    dst.setBalance(dst.getBalance().add(BigDecimal.ONE));
                    accountRepo.saveAndFlush(src);
                    accountRepo.saveAndFlush(dst);
                    return true;
                });
            } catch (RuntimeException ex) {
                if (containsAny(ex, "40P01", "deadlock")) {
                    retries.incrementAndGet();
                    // Backoff with jitter — without jitter, two retrying
                    // transactions can collide on every attempt.
                    long backoff = (long) (10 * (attempt + 1) * (0.5 + Math.random()));
                    Concurrency.quiet(backoff);
                    continue;
                }
                throw ex;
            }
        }
        return false;
    }

    // ---- helpers --------------------------------------------------------

    private <T> T safeRun(int slot, Throwable[] errs, java.util.function.Supplier<T> s) {
        try { return s.get(); }
        catch (Throwable e) { errs[slot] = e; return null; }
    }

    private static boolean containsAny(Throwable t, String... needles) {
        while (t != null) {
            String msg = t.getMessage();
            String simple = t.getClass().getSimpleName();
            if (msg != null) {
                for (String n : needles) {
                    if (msg.contains(n) || simple.toLowerCase().contains(n.toLowerCase())) return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }
}
