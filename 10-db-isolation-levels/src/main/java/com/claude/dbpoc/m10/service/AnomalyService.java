package com.claude.dbpoc.m10.service;

import com.claude.dbpoc.m10.domain.Account;
import com.claude.dbpoc.m10.domain.Transfer;
import com.claude.dbpoc.m10.repo.AccountRepository;
import com.claude.dbpoc.m10.repo.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Reproduces the classic isolation anomalies using two concurrent
 * transactions on a single Account. Every public method is a complete
 * end-to-end demo and returns the observable outcome (initial value,
 * value during the race, final value, anomaly observed yes/no).
 *
 * Each demo creates its own TransactionTemplate at the requested
 * isolation level — so READ_COMMITTED vs REPEATABLE_READ vs SERIALIZABLE
 * is a parameter, not a service-level config.
 */
@Service
public class AnomalyService {

    private final AccountRepository accountRepo;
    private final TransferRepository transferRepo;
    private final TransactionTemplate txTemplate;

    public AnomalyService(AccountRepository accountRepo,
                          TransferRepository transferRepo,
                          TransactionTemplate txTemplate) {
        this.accountRepo = accountRepo;
        this.transferRepo = transferRepo;
        this.txTemplate = txTemplate;
    }

    // ---------------------------------------------------------------------
    // Dirty read demo.
    //
    // A dirty read = T2 sees T1's uncommitted write. Postgres physically
    // cannot do this — its MVCC implementation can ONLY show committed
    // tuples. Even if you ask for READ_UNCOMMITTED (=1), Postgres upgrades
    // it to READ_COMMITTED on the wire.
    //
    // The demo proves this empirically: T1 writes & holds, T2 reads while
    // T1 is uncommitted. T2 will see the OLD value, not the dirty one.
    // ---------------------------------------------------------------------
    public Map<String, Object> dirtyRead(Long accountId) {
        BigDecimal initial = currentBalance(accountId);

        CountDownLatch t1Wrote = new CountDownLatch(1);
        CountDownLatch t2Read = new CountDownLatch(1);

        TransactionTemplate isolationLevel = clonedTemplate(TransactionDefinition.ISOLATION_READ_UNCOMMITTED);

        Concurrency.Pair<BigDecimal, BigDecimal> r = Concurrency.runBoth(
            // T1: write, hold, eventually rollback so it has no lasting effect.
            () -> isolationLevel.execute(status -> {
                Account a = accountRepo.findById(accountId).orElseThrow();
                a.setBalance(a.getBalance().add(new BigDecimal("999.00")));
                accountRepo.saveAndFlush(a);
                t1Wrote.countDown();
                Concurrency.await(t2Read);              // hold until T2 has read
                status.setRollbackOnly();                // make this write disappear
                return a.getBalance();
            }),
            // T2: read after T1 wrote-but-not-committed. On any real DB this
            // returns the OLD value (committed snapshot). The "dirty" value
            // is unreachable.
            () -> isolationLevel.execute(status -> {
                Concurrency.await(t1Wrote);
                BigDecimal seen = accountRepo.findById(accountId).orElseThrow().getBalance();
                t2Read.countDown();
                return seen;
            }),
            10
        );

        BigDecimal afterAllRolledBack = currentBalance(accountId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("anomaly", "dirty-read");
        out.put("isolationRequested", "READ_UNCOMMITTED (Postgres silently upgrades to READ_COMMITTED)");
        out.put("initialBalance", initial);
        out.put("t1WroteThenRolledBack", r.first());
        out.put("t2ObservedDuringT1Hold", r.second());
        out.put("finalBalance", afterAllRolledBack);
        out.put("dirtyReadObserved", !r.second().equals(initial));
        out.put("verdict",
            "Postgres cannot do dirty reads. T2 saw the committed value (" + initial + "), " +
            "NOT the +999 T1 was holding uncommitted. The anomaly is impossible on this engine.");
        return out;
    }

    // ---------------------------------------------------------------------
    // Non-repeatable read demo.
    //
    // T1 reads twice. Between the reads, T2 commits a new value. Under
    // READ_COMMITTED, T1's second read sees the new value. Under
    // REPEATABLE_READ (Postgres snapshot isolation) T1 sees the same value
    // both times because the snapshot is fixed at the start of the
    // transaction.
    // ---------------------------------------------------------------------
    public Map<String, Object> nonRepeatableRead(Long accountId, int isolation) {
        BigDecimal initial = currentBalance(accountId);

        CountDownLatch t1FirstRead = new CountDownLatch(1);
        CountDownLatch t2Committed = new CountDownLatch(1);

        TransactionTemplate t1Tx = clonedTemplate(isolation);

        Concurrency.Pair<BigDecimal[], BigDecimal> r = Concurrency.runBoth(
            // T1: read, wait for T2 to commit, read again.
            () -> t1Tx.execute(status -> {
                BigDecimal first = accountRepo.findById(accountId).orElseThrow().getBalance();
                t1FirstRead.countDown();
                Concurrency.await(t2Committed);
                BigDecimal second = accountRepo.findById(accountId).orElseThrow().getBalance();
                return new BigDecimal[]{ first, second };
            }),
            // T2: a separate, COMMITTED write happening between T1's two reads.
            () -> {
                Concurrency.await(t1FirstRead);
                BigDecimal newVal = txTemplate.execute(status -> {
                    Account a = accountRepo.findById(accountId).orElseThrow();
                    a.setBalance(a.getBalance().add(new BigDecimal("50.00")));
                    accountRepo.saveAndFlush(a);
                    return a.getBalance();
                });
                t2Committed.countDown();
                return newVal;
            },
            10
        );

        BigDecimal first = r.first()[0];
        BigDecimal second = r.first()[1];

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("anomaly", "non-repeatable-read");
        out.put("isolationLevel", isolationName(isolation));
        out.put("initialBalance", initial);
        out.put("t1FirstRead", first);
        out.put("t2CommittedTo", r.second());
        out.put("t1SecondRead", second);
        out.put("anomalyObserved", !first.equals(second));
        out.put("verdict",
            isolation == TransactionDefinition.ISOLATION_READ_COMMITTED
                ? "READ_COMMITTED → T1's reads diverged: " + first + " then " + second + ". " +
                  "This is the textbook non-repeatable read."
                : "REPEATABLE_READ (Postgres snapshot) → T1 saw " + first + " both times. " +
                  "The snapshot pinned at T1's start hides T2's commit.");
        return out;
    }

    // ---------------------------------------------------------------------
    // Phantom read demo.
    //
    // T1 runs a range/COUNT query twice. Between the runs T2 INSERTs a
    // matching row. Under READ_COMMITTED T1's second query has the new row.
    // Under Postgres REPEATABLE_READ the snapshot hides the new row even
    // though the SQL standard would *allow* it (only SERIALIZABLE
    // forbids phantoms in the standard). Postgres' snapshot is stronger.
    // ---------------------------------------------------------------------
    public Map<String, Object> phantomRead(int isolation) {
        BigDecimal threshold = new BigDecimal("100.00");

        CountDownLatch t1FirstCount = new CountDownLatch(1);
        CountDownLatch t2Inserted = new CountDownLatch(1);

        TransactionTemplate t1Tx = clonedTemplate(isolation);

        Concurrency.Pair<long[], Long> r = Concurrency.runBoth(
            () -> t1Tx.execute(status -> {
                long first = transferRepo.countByAmountGreaterThan(threshold);
                t1FirstCount.countDown();
                Concurrency.await(t2Inserted);
                long second = transferRepo.countByAmountGreaterThan(threshold);
                return new long[]{ first, second };
            }),
            () -> {
                Concurrency.await(t1FirstCount);
                Long inserted = txTemplate.execute(status -> {
                    Transfer t = transferRepo.save(new Transfer(1L, 2L, new BigDecimal("500.00")));
                    return t.getId();
                });
                t2Inserted.countDown();
                return inserted;
            },
            10
        );

        long first = r.first()[0];
        long second = r.first()[1];

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("anomaly", "phantom-read");
        out.put("isolationLevel", isolationName(isolation));
        out.put("predicate", "amount > " + threshold);
        out.put("t1FirstCount", first);
        out.put("t2InsertedRowId", r.second());
        out.put("t1SecondCount", second);
        out.put("phantomObserved", first != second);
        out.put("verdict",
            isolation == TransactionDefinition.ISOLATION_READ_COMMITTED
                ? "READ_COMMITTED → T1 saw " + first + " then " + second + ". Phantom appeared."
                : "Postgres " + isolationName(isolation) + " (snapshot isolation) → both counts " +
                  "were " + first + ". No phantom, even though the SQL standard would permit one.");
        return out;
    }

    // ---------------------------------------------------------------------
    // Lost update — the unfixed version. This is the bug. Two concurrent
    // transactions both read the SAME starting balance, both add $50, both
    // write back. The second write overwrites the first. $100 + $50 + $50
    // → expected $200, observed $150.
    // ---------------------------------------------------------------------
    public Map<String, Object> lostUpdate(Long accountId) {
        BigDecimal initial = currentBalance(accountId);
        BigDecimal addend = new BigDecimal("50.00");

        CountDownLatch bothRead = new CountDownLatch(2);

        TransactionTemplate readCommitted = clonedTemplate(TransactionDefinition.ISOLATION_READ_COMMITTED);

        Concurrency.Pair<BigDecimal, BigDecimal> r = Concurrency.runBoth(
            () -> readCommitted.execute(status -> {
                Account a = accountRepo.findById(accountId).orElseThrow();
                BigDecimal seen = a.getBalance();
                bothRead.countDown();
                Concurrency.await(bothRead);             // hold until both threads read
                a.setBalance(seen.add(addend));
                accountRepo.saveAndFlush(a);
                return a.getBalance();
            }),
            () -> readCommitted.execute(status -> {
                Account a = accountRepo.findById(accountId).orElseThrow();
                BigDecimal seen = a.getBalance();
                bothRead.countDown();
                Concurrency.await(bothRead);
                Concurrency.quiet(50);                   // ensure T1 commits first
                a.setBalance(seen.add(addend));
                accountRepo.saveAndFlush(a);
                return a.getBalance();
            }),
            10
        );

        BigDecimal finalVal = currentBalance(accountId);
        BigDecimal expected = initial.add(addend).add(addend);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("anomaly", "lost-update");
        out.put("isolationLevel", "READ_COMMITTED");
        out.put("initialBalance", initial);
        out.put("t1WroteBack", r.first());
        out.put("t2WroteBack", r.second());
        out.put("finalBalance", finalVal);
        out.put("expectedIfNoLoss", expected);
        out.put("lostMoney", expected.subtract(finalVal));
        out.put("verdict",
            "Lost update: expected " + expected + ", got " + finalVal + ". " +
            "Both transactions read " + initial + ", both wrote +50. The second write " +
            "clobbered the first. In a fintech system this is money disappearing.");
        return out;
    }

    private BigDecimal currentBalance(Long accountId) {
        return txTemplate.execute(status -> accountRepo.findById(accountId).orElseThrow().getBalance());
    }

    private TransactionTemplate clonedTemplate(int isolation) {
        TransactionTemplate t = new TransactionTemplate(txTemplate.getTransactionManager());
        t.setIsolationLevel(isolation);
        return t;
    }

    static String isolationName(int level) {
        return switch (level) {
            case TransactionDefinition.ISOLATION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
            case TransactionDefinition.ISOLATION_READ_COMMITTED   -> "READ_COMMITTED";
            case TransactionDefinition.ISOLATION_REPEATABLE_READ  -> "REPEATABLE_READ";
            case TransactionDefinition.ISOLATION_SERIALIZABLE     -> "SERIALIZABLE";
            default -> "DEFAULT";
        };
    }
}
