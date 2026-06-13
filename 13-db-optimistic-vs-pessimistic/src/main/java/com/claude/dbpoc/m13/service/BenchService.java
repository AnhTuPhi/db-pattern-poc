package com.claude.dbpoc.m13.service;

import com.claude.dbpoc.m13.domain.Account;
import com.claude.dbpoc.m13.repo.AccountRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The benchmark. Three methods, one per strategy. Each method runs
 * `threads` workers in parallel; each worker loops `iterationsPerThread`
 * times doing "read $balance, +$1, write back" against either a single
 * hot row or a partitioned subset.
 *
 * Design notes:
 *
 *   1. CountDownLatch startGate — all threads block on it until the main
 *      thread releases them simultaneously. Without this, the first thread
 *      gets a head start (warm caches, JIT) and the timing is skewed.
 *
 *   2. nanoTime taken AFTER the start gate fires and BEFORE awaiting the
 *      workers, so we measure wall-clock contention, not setup overhead.
 *
 *   3. Each iteration is its own transaction (TransactionTemplate.execute).
 *      That's intentional: a single big tx wrapping all iterations would
 *      collapse the benchmark to "one giant lock, no retries observed" —
 *      which isn't representative of any production code.
 *
 *   4. Retry budget = 5. On a contended hot row with 16 threads, optimistic
 *      can chew through dozens of retries per iteration; bounding it keeps
 *      a pathological case from running away, and the retry COUNT in the
 *      response tells you whether you're in that regime.
 *
 *   5. accountId selection:
 *        - hotRow=true   → every thread hits accountId=1.
 *        - hotRow=false  → thread i hits accountId = (i % accountCount) + 1.
 *      Dispersed mode is the "low conflict" case where optimistic and CAS
 *      shine. Hot mode is the "high conflict" case where pessimistic wins.
 *      The crossover between them is the whole lesson of the module.
 *
 *   6. Result map shape is identical across the three methods so the
 *      bench/all endpoint can stack them in a comparison table.
 */
@Service
public class BenchService {

    private final AccountRepository accountRepo;
    private final TransactionTemplate txTemplate;

    /** Reused across all iterations. BigDecimal is immutable so this is safe. */
    private static final BigDecimal ONE = new BigDecimal("1.00");

    /** Bounded retry budget for the optimistic path. Higher = more CPU burnt under contention. */
    private static final int MAX_RETRIES = 5;

    public BenchService(AccountRepository accountRepo, TransactionTemplate txTemplate) {
        this.accountRepo = accountRepo;
        this.txTemplate = txTemplate;
    }

    // ---------------------------------------------------------------------
    // STRATEGY 1 — OPTIMISTIC (@Version + retry).
    //
    // Each iteration:
    //   1. read Account (loads version=N)
    //   2. compute balance + 1
    //   3. saveAndFlush → UPDATE ... WHERE id=? AND version=N
    //   4. on conflict (0 rows updated) → catch OOL, retry up to MAX_RETRIES
    //
    // Hot-row prediction: very high retry count → low ops/sec.
    // Dispersed prediction: near-zero retries → matches or beats CAS.
    // ---------------------------------------------------------------------
    public Map<String, Object> runOptimistic(int threads, int iterationsPerThread,
                                              boolean hotRow, int accountCount) {
        AtomicLong totalRetries = new AtomicLong();
        AtomicLong totalOps = new AtomicLong();

        BenchResult r = runBench(threads, iterationsPerThread, (threadIndex) -> {
            long accountId = pickAccountId(threadIndex, hotRow, accountCount);
            for (int i = 0; i < iterationsPerThread; i++) {
                int attempts = 0;
                while (true) {
                    try {
                        txTemplate.execute(status -> {
                            Account a = accountRepo.findById(accountId).orElseThrow();
                            a.setBalance(a.getBalance().add(ONE));
                            accountRepo.saveAndFlush(a);
                            return null;
                        });
                        totalOps.incrementAndGet();
                        break;
                    } catch (ObjectOptimisticLockingFailureException ool) {
                        totalRetries.incrementAndGet();
                        attempts++;
                        if (attempts >= MAX_RETRIES) {
                            // Budget exhausted. In production you'd 409 the caller; here
                            // we just stop counting this iteration as a successful op.
                            break;
                        }
                        // Tiny backoff so we don't busy-loop the row-lock manager.
                        sleepQuiet(1L);
                    }
                }
            }
        });

        return buildResult("optimistic", threads, iterationsPerThread, hotRow, accountCount,
                           totalOps.get(), totalRetries.get(), r.elapsedMs);
    }

    // ---------------------------------------------------------------------
    // STRATEGY 2 — PESSIMISTIC (SELECT ... FOR UPDATE).
    //
    // Each iteration:
    //   1. begin tx
    //   2. findByIdForUpdate → SELECT ... FOR UPDATE acquires row lock
    //   3. compute balance + 1
    //   4. saveAndFlush → UPDATE ...
    //   5. commit → release lock
    //
    // There is no application-level retry: contention manifests as wait
    // time on the lock, not as exceptions. Postgres serialises FOR UPDATE
    // callers in a FIFO queue. On a hot row this is by far the fastest
    // strategy because every iteration commits exactly once.
    //
    // Hot-row prediction: 0 retries, highest ops/sec.
    // Dispersed prediction: wasted ceremony — the lock is uncontended but
    // we still pay for SELECT ... FOR UPDATE + the lock manager bookkeeping.
    // ---------------------------------------------------------------------
    public Map<String, Object> runPessimistic(int threads, int iterationsPerThread,
                                               boolean hotRow, int accountCount) {
        AtomicLong totalOps = new AtomicLong();

        BenchResult r = runBench(threads, iterationsPerThread, (threadIndex) -> {
            long accountId = pickAccountId(threadIndex, hotRow, accountCount);
            for (int i = 0; i < iterationsPerThread; i++) {
                txTemplate.execute(status -> {
                    Account a = accountRepo.findByIdForUpdate(accountId).orElseThrow();
                    a.setBalance(a.getBalance().add(ONE));
                    accountRepo.saveAndFlush(a);
                    return null;
                });
                totalOps.incrementAndGet();
            }
        });

        return buildResult("pessimistic", threads, iterationsPerThread, hotRow, accountCount,
                           totalOps.get(), 0L, r.elapsedMs);
    }

    // ---------------------------------------------------------------------
    // STRATEGY 3 — CAS (UPDATE ... SET balance = balance + ?).
    //
    // Each iteration is a single statement; no entity load, no version
    // check at the app layer, no application-side retry needed. Postgres
    // takes a row lock for the duration of the UPDATE only and releases
    // it at commit. Throughput-wise this is the cheapest correct option.
    //
    // Hot-row prediction: best raw throughput because there's no read.
    // Dispersed prediction: even better — no lock contention at all.
    //
    // The pattern's limitation is that the new value must be expressible
    // as a SQL expression over the old one. Business rules like
    // "if balance < 0 then reject" can be folded into the WHERE, but
    // anything that needs the OLD value back in the application is
    // structurally incompatible with this strategy.
    // ---------------------------------------------------------------------
    public Map<String, Object> runCas(int threads, int iterationsPerThread,
                                       boolean hotRow, int accountCount) {
        AtomicLong totalOps = new AtomicLong();

        BenchResult r = runBench(threads, iterationsPerThread, (threadIndex) -> {
            long accountId = pickAccountId(threadIndex, hotRow, accountCount);
            for (int i = 0; i < iterationsPerThread; i++) {
                txTemplate.execute(status -> {
                    int rows = accountRepo.addToBalance(accountId, ONE);
                    if (rows != 1) {
                        throw new IllegalStateException("CAS hit zero rows for id=" + accountId);
                    }
                    return null;
                });
                totalOps.incrementAndGet();
            }
        });

        return buildResult("cas", threads, iterationsPerThread, hotRow, accountCount,
                           totalOps.get(), 0L, r.elapsedMs);
    }

    // ---- helpers --------------------------------------------------------

    /**
     * Run the worker on `threads` parallel threads with a start-gate so they
     * all release simultaneously. Returns elapsed wall-clock ms from the
     * gate-release to the last worker finishing.
     *
     * Why we don't use ForkJoinPool / parallelStream:
     *   - We want a known, fixed number of threads so the contention level
     *     is reproducible.
     *   - parallelStream's commonPool size depends on CPU count, which
     *     would make the numbers unstable across machines.
     */
    private BenchResult runBench(int threads, int iterationsPerThread, BenchWorker worker) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger anyFailure = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        // Block until the main thread says "go". This is the
                        // synchronisation primitive that gives all threads the
                        // same starting line and makes the wall-clock timing
                        // honest.
                        startGate.await();
                        worker.run(tid);
                    } catch (Throwable ex) {
                        // Worker exceptions are surfaced via a counter so a
                        // single bad iteration doesn't poison the whole run.
                        // We still print so the dev sees the cause.
                        anyFailure.incrementAndGet();
                        ex.printStackTrace();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            long startNanos = System.nanoTime();
            startGate.countDown();                       // release all workers
            try {
                // Generous timeout: a runaway optimistic loop on a hot row
                // can be slow but still finishes. If we genuinely deadlock
                // something, we want the exception, not an infinite hang.
                doneGate.await(5, TimeUnit.MINUTES);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            long elapsedNanos = System.nanoTime() - startNanos;

            return new BenchResult(elapsedNanos / 1_000_000L, anyFailure.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Hot row → all threads hammer id=1. Dispersed → round-robin across
     * accountCount rows. accountCount is clamped to >=1 to avoid div-by-zero
     * if a caller passes accounts=0.
     */
    private long pickAccountId(int threadIndex, boolean hotRow, int accountCount) {
        if (hotRow) return 1L;
        int n = Math.max(1, accountCount);
        return (threadIndex % n) + 1L;
    }

    private Map<String, Object> buildResult(String strategy, int threads, int iterations,
                                             boolean hotRow, int accountCount,
                                             long totalOps, long totalRetries, long elapsedMs) {
        // Avoid divide-by-zero on a degenerate 0-elapsed run (impossible in
        // practice but cheap to guard).
        double opsPerSec = elapsedMs == 0 ? 0 : (totalOps * 1000.0) / elapsedMs;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", strategy);
        out.put("threads", threads);
        out.put("iterationsPerThread", iterations);
        out.put("hotRow", hotRow);
        out.put("accountCount", accountCount);
        out.put("totalOps", totalOps);
        out.put("totalRetries", totalRetries);
        out.put("elapsedMs", elapsedMs);
        out.put("opsPerSec", Math.round(opsPerSec));
        return out;
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Functional interface for the per-thread body. Picking our own keeps the lambda compact. */
    @FunctionalInterface
    private interface BenchWorker {
        void run(int threadIndex);
    }

    /** Carries the wall-clock result back from runBench to the strategy method. */
    private record BenchResult(long elapsedMs, int workerFailures) {}
}
