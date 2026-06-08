package com.claude.dbpoc.m10.service;

import com.claude.dbpoc.m10.domain.Account;
import com.claude.dbpoc.m10.repo.AccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Five fixes for lost update, each in its own method. Each fix runs the
 * SAME concurrent-update scenario as AnomalyService.lostUpdate but
 * arranges for the second write to either fail (so the caller can retry)
 * or to see the first write's effect.
 *
 * The five are the production-grade options. Order in the response = order
 * of preference for most use cases:
 *
 *   1. select-for-update — pessimistic. Most common in fintech.
 *   2. optimistic        — JPA @Version. Cheap, retries on conflict.
 *   3. cas-update        — single UPDATE with WHERE balance=?. No PC needed.
 *   4. retry             — wrap the optimistic version in a loop.
 *   5. serializable      — let Postgres SSI catch it via 40001.
 */
@Service
public class LostUpdateFixService {

    private final AccountRepository accountRepo;
    private final TransactionTemplate txTemplate;

    @PersistenceContext
    private EntityManager em;

    public LostUpdateFixService(AccountRepository accountRepo, TransactionTemplate txTemplate) {
        this.accountRepo = accountRepo;
        this.txTemplate = txTemplate;
    }

    // ---------------------------------------------------------------------
    // FIX 1 — SELECT ... FOR UPDATE (pessimistic write lock).
    //
    // T1's read takes a row-level write lock. T2's read blocks until T1
    // commits. By the time T2 runs the +50 it sees T1's $150, so the
    // final value is $200. Correct, easy to reason about, blocks readers
    // that also want FOR UPDATE — but plain readers (MVCC) are unaffected.
    // ---------------------------------------------------------------------
    public Map<String, Object> selectForUpdate(Long accountId) {
        BigDecimal initial = balance(accountId);
        BigDecimal addend = new BigDecimal("50.00");

        TransactionTemplate tx = readCommitted();
        CountDownLatch t1Locked = new CountDownLatch(1);

        Concurrency.Pair<BigDecimal, BigDecimal> r = Concurrency.runBoth(
            () -> tx.execute(status -> {
                Account a = accountRepo.findByIdForUpdate(accountId).orElseThrow();
                t1Locked.countDown();
                Concurrency.quiet(150);                 // hold lock long enough for T2 to attempt
                a.setBalance(a.getBalance().add(addend));
                accountRepo.saveAndFlush(a);
                return a.getBalance();
            }),
            () -> tx.execute(status -> {
                Concurrency.await(t1Locked);
                // This BLOCKS inside Postgres until T1 commits. When it
                // returns, the account row reflects T1's write.
                Account a = accountRepo.findByIdForUpdate(accountId).orElseThrow();
                a.setBalance(a.getBalance().add(addend));
                accountRepo.saveAndFlush(a);
                return a.getBalance();
            }),
            10
        );

        BigDecimal finalVal = balance(accountId);
        BigDecimal expected = initial.add(addend).add(addend);
        return result("select-for-update", initial, expected, finalVal,
            "T1's FOR UPDATE locked the row. T2 blocked on its own FOR UPDATE until T1 committed. " +
            "T2 then saw $" + r.first() + " and added $50 → $" + r.second() + ". No money lost.",
            null);
    }

    // ---------------------------------------------------------------------
    // FIX 2 — Optimistic locking via @Version.
    //
    // Both transactions read the row with version=N. Both compute +50,
    // both flush UPDATE ... WHERE id=? AND version=N. The second one
    // gets zero rows affected; Hibernate throws
    // ObjectOptimisticLockingFailureException. The CALLER is expected to
    // retry (see retryOptimistic). Without the retry, you trade silent
    // money loss for a 409.
    // ---------------------------------------------------------------------
    public Map<String, Object> optimistic(Long accountId) {
        BigDecimal initial = balance(accountId);
        BigDecimal addend = new BigDecimal("50.00");
        TransactionTemplate tx = readCommitted();
        CountDownLatch bothRead = new CountDownLatch(2);

        Throwable[] errs = new Throwable[2];

        Concurrency.Pair<BigDecimal, BigDecimal> r = Concurrency.runBoth(
            () -> safeRun(0, errs, () -> tx.execute(status -> {
                Account a = accountRepo.findById(accountId).orElseThrow();
                BigDecimal seen = a.getBalance();
                bothRead.countDown();
                Concurrency.await(bothRead);
                a.setBalance(seen.add(addend));
                accountRepo.saveAndFlush(a);
                return a.getBalance();
            })),
            () -> safeRun(1, errs, () -> tx.execute(status -> {
                Account a = accountRepo.findById(accountId).orElseThrow();
                BigDecimal seen = a.getBalance();
                bothRead.countDown();
                Concurrency.await(bothRead);
                Concurrency.quiet(50);
                a.setBalance(seen.add(addend));
                accountRepo.saveAndFlush(a);
                return a.getBalance();
            })),
            10
        );

        BigDecimal finalVal = balance(accountId);
        BigDecimal expected = initial.add(addend).add(addend);

        // Exactly one of the two transactions should have failed with OOL.
        String firstErr = errs[0] == null ? null : errs[0].getClass().getSimpleName();
        String secondErr = errs[1] == null ? null : errs[1].getClass().getSimpleName();

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("t1Result", r.first() == null ? "failed: " + firstErr : r.first().toPlainString());
        extras.put("t2Result", r.second() == null ? "failed: " + secondErr : r.second().toPlainString());
        if (firstErr != null) extras.put("t1Exception", firstErr);
        if (secondErr != null) extras.put("t2Exception", secondErr);

        return result("optimistic", initial, expected, finalVal,
            "T1 wrote → version 0→1. T2 attempted UPDATE ... WHERE version=0 → 0 rows. " +
            "Hibernate threw ObjectOptimisticLockingFailureException. Final balance reflects ONE " +
            "successful write. The failed transaction's job is to retry — see /lost-update/retry.",
            extras);
    }

    // ---------------------------------------------------------------------
    // FIX 3 — CAS UPDATE (compare-and-set in a single statement).
    //
    // Skip the read-then-write dance entirely. Issue:
    //   UPDATE account SET balance = balance + 50 WHERE id = ?
    // Postgres holds a row-level lock for the duration of THIS statement;
    // two concurrent UPDATEs are serialised by row lock, both succeed,
    // result is $200. Cheapest correct fix. The drawback: you can't make
    // the new value depend on application logic that needs the old value.
    // ---------------------------------------------------------------------
    public Map<String, Object> casUpdate(Long accountId) {
        BigDecimal initial = balance(accountId);
        BigDecimal addend = new BigDecimal("50.00");
        TransactionTemplate tx = readCommitted();

        Concurrency.Pair<Integer, Integer> r = Concurrency.runBoth(
            () -> tx.execute(status -> em.createQuery(
                    "UPDATE Account a SET a.balance = a.balance + :amt, a.version = a.version + 1 WHERE a.id = :id")
                .setParameter("amt", addend)
                .setParameter("id", accountId)
                .executeUpdate()),
            () -> tx.execute(status -> em.createQuery(
                    "UPDATE Account a SET a.balance = a.balance + :amt, a.version = a.version + 1 WHERE a.id = :id")
                .setParameter("amt", addend)
                .setParameter("id", accountId)
                .executeUpdate()),
            10
        );

        BigDecimal finalVal = balance(accountId);
        BigDecimal expected = initial.add(addend).add(addend);
        return result("cas-update", initial, expected, finalVal,
            "Two UPDATE ... SET balance = balance + 50 statements. Postgres serialises by row lock. " +
            "Both updated exactly 1 row (t1=" + r.first() + ", t2=" + r.second() + "). Final = $" + finalVal + ".",
            null);
    }

    // ---------------------------------------------------------------------
    // FIX 4 — retry-on-conflict around the optimistic version.
    //
    // The OOL exception is the signal to retry the whole business
    // operation (re-read, re-compute, re-write). With a bounded retry
    // budget, this is the gold standard for high-concurrency low-conflict
    // workloads — no blocking, no manual locks, just bounded retries.
    // ---------------------------------------------------------------------
    public Map<String, Object> retryOptimistic(Long accountId) {
        BigDecimal initial = balance(accountId);
        BigDecimal addend = new BigDecimal("50.00");
        TransactionTemplate tx = readCommitted();
        AtomicInteger retries = new AtomicInteger();

        Concurrency.Pair<BigDecimal, BigDecimal> r = Concurrency.runBoth(
            () -> retryingAdd(accountId, addend, tx, retries),
            () -> retryingAdd(accountId, addend, tx, retries),
            10
        );

        BigDecimal finalVal = balance(accountId);
        BigDecimal expected = initial.add(addend).add(addend);
        return result("retry-optimistic", initial, expected, finalVal,
            "Whichever thread lost the race retried — re-read $" + initial.add(addend) + ", added $50 → $200. " +
            "Total retries observed: " + retries.get() + ". Acceptable when conflicts are rare.",
            Map.of("retries", retries.get()));
    }

    /** Re-read, re-apply, retry on optimistic conflict. Bounded so we don't loop forever. */
    private BigDecimal retryingAdd(Long accountId, BigDecimal addend, TransactionTemplate tx, AtomicInteger retries) {
        int max = 5;
        for (int attempt = 0; attempt < max; attempt++) {
            try {
                return tx.execute(status -> {
                    Account a = accountRepo.findById(accountId).orElseThrow();
                    a.setBalance(a.getBalance().add(addend));
                    accountRepo.saveAndFlush(a);
                    return a.getBalance();
                });
            } catch (ObjectOptimisticLockingFailureException e) {
                retries.incrementAndGet();
                Concurrency.quiet(10L * (attempt + 1));   // tiny backoff
            }
        }
        throw new RuntimeException("retry budget exhausted after " + max + " attempts");
    }

    // ---------------------------------------------------------------------
    // FIX 5 — SERIALIZABLE (Postgres SSI).
    //
    // Postgres' SERIALIZABLE = SSI. Concurrent read-then-write patterns
    // that would otherwise produce a lost update are detected at commit
    // time; the loser gets SQLSTATE 40001 (serialization_failure). Caller
    // is expected to retry the whole transaction. Cleanest semantics,
    // highest abort rate under contention.
    // ---------------------------------------------------------------------
    public Map<String, Object> serializable(Long accountId) {
        BigDecimal initial = balance(accountId);
        BigDecimal addend = new BigDecimal("50.00");
        TransactionTemplate tx = clonedTemplate(TransactionDefinition.ISOLATION_SERIALIZABLE);
        AtomicInteger retries = new AtomicInteger();
        CountDownLatch bothRead = new CountDownLatch(2);

        Concurrency.Pair<BigDecimal, BigDecimal> r = Concurrency.runBoth(
            () -> serializableRetry(accountId, addend, tx, retries, bothRead),
            () -> serializableRetry(accountId, addend, tx, retries, bothRead),
            10
        );

        BigDecimal finalVal = balance(accountId);
        BigDecimal expected = initial.add(addend).add(addend);
        return result("serializable", initial, expected, finalVal,
            "SERIALIZABLE → SSI detected the read-write conflict, aborted the loser with 40001, " +
            "retry succeeded. Retries observed: " + retries.get() + ".",
            Map.of("retries", retries.get()));
    }

    private BigDecimal serializableRetry(Long accountId, BigDecimal addend, TransactionTemplate tx,
                                          AtomicInteger retries, CountDownLatch bothRead) {
        int max = 5;
        for (int attempt = 0; attempt < max; attempt++) {
            try {
                return tx.execute(status -> {
                    Account a = accountRepo.findById(accountId).orElseThrow();
                    BigDecimal seen = a.getBalance();
                    bothRead.countDown();
                    // Only wait on the very first attempt — that's the
                    // collision-engineering window. Retries should not block.
                    if (attempt == 0) Concurrency.await(bothRead);
                    a.setBalance(seen.add(addend));
                    accountRepo.saveAndFlush(a);
                    return a.getBalance();
                });
            } catch (RuntimeException ex) {
                // org.postgresql.util.PSQLException wrapped — message contains 40001.
                if (isSerializationFailure(ex)) {
                    retries.incrementAndGet();
                    Concurrency.quiet(15L * (attempt + 1));
                    continue;
                }
                throw ex;
            }
        }
        throw new RuntimeException("serializable retry budget exhausted");
    }

    private static boolean isSerializationFailure(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("40001") || msg.toLowerCase().contains("serialization"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    // ---- helpers --------------------------------------------------------

    private <T> T safeRun(int slot, Throwable[] errs, java.util.function.Supplier<T> s) {
        try { return s.get(); }
        catch (Throwable e) { errs[slot] = e; return null; }
    }

    private BigDecimal balance(Long accountId) {
        return txTemplate.execute(status -> accountRepo.findById(accountId).orElseThrow().getBalance());
    }

    private TransactionTemplate readCommitted() {
        return clonedTemplate(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private TransactionTemplate clonedTemplate(int isolation) {
        TransactionTemplate t = new TransactionTemplate(txTemplate.getTransactionManager());
        t.setIsolationLevel(isolation);
        return t;
    }

    private Map<String, Object> result(String fix, BigDecimal initial, BigDecimal expected,
                                        BigDecimal finalVal, String verdict, Map<String, Object> extras) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fix", fix);
        out.put("initialBalance", initial);
        out.put("expected", expected);
        out.put("finalBalance", finalVal);
        out.put("anomalyPrevented", expected.compareTo(finalVal) == 0);
        out.put("verdict", verdict);
        if (extras != null) out.putAll(extras);
        return out;
    }
}
