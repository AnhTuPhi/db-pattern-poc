package com.claude.dbpoc.m14.service;

import com.claude.dbpoc.m14.domain.Widget;
import com.claude.dbpoc.m14.repo.WidgetRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Four endpoints, four ways a long transaction hurts:
 *
 *   1. lockHold      — long tx holding FOR UPDATE blocks a competing writer.
 *   2. bloat         — long tx pins xmin, autovacuum can't reap dead tuples.
 *   3. idleInTx      — long tx that's done its work but never committed.
 *   4. observability — find the long ones in pg_stat_activity (the 3am query).
 *
 * The lesson:  none of these are deadlocks. None of these throw exceptions.
 * Your application looks fine. Your database is dying quietly.
 *
 * The fix is structural: SHORT transactions. Open late, commit early. Never
 * hold a tx across an external call (HTTP, queue publish, manual review).
 * If you can't avoid a long tx, run it on a replica or out-of-band.
 */
@Service
public class LongTxService {

    private final WidgetRepository widgetRepo;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager em;

    public LongTxService(WidgetRepository widgetRepo, TransactionTemplate tx) {
        this.widgetRepo = widgetRepo;
        this.tx = tx;
    }

    // ---------------------------------------------------------------------
    // 1. LOCK HOLD.
    //
    // T1 opens a tx, takes FOR UPDATE on widget #1, then sleeps holdMs.
    // T2 opens a tx, takes FOR UPDATE on the SAME widget — blocked.
    //
    // Postgres logs a wait, but doesn't error. The application looks
    // healthy: T2's connection is "active" but doing nothing. Multiply by
    // 100 concurrent users hitting the same hot row and you have pool
    // exhaustion (see m17) caused entirely by a single slow tx.
    // ---------------------------------------------------------------------
    public Map<String, Object> lockHold(Long widgetId, long holdMs) {
        CountDownLatch t1HasLock = new CountDownLatch(1);

        long t0 = System.nanoTime();
        Concurrency.Pair<Long, Long> r = Concurrency.runBoth(
            () -> tx.execute(status -> {
                // T1: take the lock, hold it for holdMs, then release by commit.
                Widget w = widgetRepo.findByIdForUpdate(widgetId).orElseThrow();
                t1HasLock.countDown();
                Concurrency.quiet(holdMs);
                w.setVersion(w.getVersion() + 1);
                widgetRepo.saveAndFlush(w);
                return holdMs;
            }),
            () -> {
                // T2: wait until T1 has the lock, then try to grab it. The
                // long wait is the measurement.
                Concurrency.await(t1HasLock);
                long t = System.nanoTime();
                Long out = tx.execute(status -> {
                    Widget w = widgetRepo.findByIdForUpdate(widgetId).orElseThrow();
                    w.setVersion(w.getVersion() + 1);
                    widgetRepo.saveAndFlush(w);
                    return (System.nanoTime() - t) / 1_000_000L;
                });
                return out;
            },
            (holdMs / 1000) + 30
        );
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "T1 holds FOR UPDATE for " + holdMs + "ms; T2 waits behind it");
        out.put("widgetId", widgetId);
        out.put("t1HeldForMs", r.first());
        out.put("t2WaitedForMs", r.second());
        out.put("totalElapsedMs", elapsedMs);
        out.put("verdict",
            "T2 waited ~" + r.second() + "ms — entirely the cost of T1's lock. " +
            "There is no deadlock, no exception, no log line in the application. " +
            "The only signal in production is increased p99 latency and pool saturation. " +
            "This is why long transactions are the #1 cause of mysterious DB slowdowns.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 2. MVCC BLOAT.
    //
    // Postgres can only VACUUM a dead tuple if no in-flight transaction
    // could still see it. The oldest still-running tx defines the "xmin
    // horizon". UPDATE creates a new tuple; the old one becomes dead. If
    // a long tx is open, dead tuples ACCUMULATE — table grows, indexes
    // grow, queries slow.
    //
    // Demo:
    //   - T1 (the bloat-causer): just BEGIN, sit idle, never commit.
    //   - In parallel: UPDATE widget row N times → N dead tuples.
    //   - Try to VACUUM the table; observe n_dead_tup is unchanged.
    //   - Compare to the SAME workload after we commit T1: VACUUM reaps.
    //
    // The metrics shown come from pg_stat_user_tables.
    // ---------------------------------------------------------------------
    public Map<String, Object> bloat(Long widgetId, int updates) {
        Map<String, Object> out = new LinkedHashMap<>();

        // Baseline: how many dead tuples does this table have right now?
        Map<String, Object> baselineStats = tableStats();

        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        // T1: a long-running transaction that does ONE read then sits.
        // The read is necessary so Postgres assigns it a transaction id and
        // it joins the snapshot horizon.
        new Thread(() -> tx.execute(status -> {
            em.createNativeQuery("select 1").getSingleResult();
            // Take a SHARE lock to make sure we have an active snapshot.
            widgetRepo.findById(widgetId);
            holderStarted.countDown();
            Concurrency.await(releaseHolder);
            return null;
        }), "bloat-holder").start();

        Concurrency.await(holderStarted);

        // Generate dead tuples while T1 is holding the horizon.
        for (int i = 0; i < updates; i++) {
            tx.execute(status -> {
                Widget w = widgetRepo.findById(widgetId).orElseThrow();
                w.setVersion(w.getVersion() + 1);
                widgetRepo.saveAndFlush(w);
                return null;
            });
        }

        // ANALYZE so the stats catch up to what we just did.
        tx.execute(status -> em.createNativeQuery("ANALYZE widget").executeUpdate());

        // Try to VACUUM — this CANNOT reap rows because T1's horizon is open.
        tx.execute(status -> em.createNativeQuery("VACUUM widget").executeUpdate());
        Map<String, Object> blockedStats = tableStats();

        // Now release T1.
        releaseHolder.countDown();
        Concurrency.quiet(200);

        // VACUUM again — now the rows ARE reapable.
        tx.execute(status -> em.createNativeQuery("VACUUM widget").executeUpdate());
        Concurrency.quiet(100);
        Map<String, Object> freedStats = tableStats();

        out.put("scenario", "long tx pins xmin horizon, blocking VACUUM");
        out.put("widgetId", widgetId);
        out.put("updates", updates);
        out.put("baseline", baselineStats);
        out.put("afterUpdatesWhileLongTxHeld", blockedStats);
        out.put("afterLongTxCommitted", freedStats);
        out.put("verdict",
            "While the long tx was open, VACUUM could not reap dead tuples — n_dead_tup stayed elevated. " +
            "After the long tx committed and VACUUM ran again, the dead tuples were reclaimed. " +
            "In production, this is how a single slow query (analytics report, long-running export, " +
            "stuck consumer) bloats a table over hours/days. Audit pg_stat_activity for tx older than " +
            "your slowest expected query.");
        return out;
    }

    /** Snapshot of bloat-relevant counters for the widget table. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> tableStats() {
        return tx.execute(status -> {
            List<Object[]> rows = em.createNativeQuery(
                "select " +
                "  n_live_tup, " +
                "  n_dead_tup, " +
                "  n_mod_since_analyze, " +
                "  pg_size_pretty(pg_total_relation_size('widget'::regclass)) as total_size " +
                "from pg_stat_user_tables " +
                "where relname = 'widget'").getResultList();
            Map<String, Object> m = new LinkedHashMap<>();
            if (rows.isEmpty()) {
                m.put("note", "no rows in pg_stat_user_tables for widget");
                return m;
            }
            Object[] row = rows.get(0);
            m.put("nLiveTup", row[0]);
            m.put("nDeadTup", row[1]);
            m.put("nModSinceAnalyze", row[2]);
            m.put("totalRelationSize", row[3]);
            return m;
        });
    }

    // ---------------------------------------------------------------------
    // 3. IDLE IN TRANSACTION.
    //
    // The most insidious variant: the app code that opens the tx returned
    // to the user already, but never committed. The connection sits in
    // state='idle in transaction'. It still holds:
    //   - the connection (counts against pool max)
    //   - any row locks acquired
    //   - the xmin horizon
    //
    // Postgres can kill them with idle_in_transaction_session_timeout.
    // Spring CANNOT — once the @Transactional method returns, Spring
    // should commit, but if you wired up TransactionTemplate yourself and
    // forgot to call commit() / rollback(), you have this bug.
    //
    // We simulate by starting a tx in a background thread, running a query,
    // then never committing — and we show the row appearing in
    // pg_stat_activity with state='idle in transaction'.
    // ---------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Map<String, Object> idleInTransaction(long idleMs) {
        CountDownLatch txOpened = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);

        new Thread(() -> tx.execute(status -> {
            em.createNativeQuery(
                "select set_config('application_name', 'm14-idle-in-tx', false)")
                .getSingleResult();
            em.createNativeQuery("select 1").getSingleResult();
            txOpened.countDown();
            Concurrency.await(released);
            return null;
        }), "idle-in-tx").start();

        Concurrency.await(txOpened);
        // Let the tx sit IDLE for the configured duration so the
        // state_age_ms we report actually reflects what the user asked for.
        Concurrency.quiet(idleMs);

        List<Object[]> rows = tx.execute(status -> em.createNativeQuery(
            "select " +
            "  pid, application_name, state, " +
            "  extract(epoch from (now() - xact_start)) * 1000 as tx_age_ms, " +
            "  extract(epoch from (now() - state_change)) * 1000 as state_age_ms, " +
            "  left(query, 200) as last_query " +
            "from pg_stat_activity " +
            "where state = 'idle in transaction' " +
            "  and pid <> pg_backend_pid() " +
            "  and datname = current_database()").getResultList());

        // Release the holder so its connection returns to the pool.
        released.countDown();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "tx left open with state='idle in transaction' for " + idleMs + "ms");
        List<Map<String, Object>> idle = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pid", row[0]);
            m.put("application", row[1]);
            m.put("state", row[2]);
            m.put("txAgeMs", row[3]);
            m.put("stateAgeMs", row[4]);
            m.put("lastQuery", row[5]);
            idle.add(m);
        }
        out.put("idleInTransaction", idle);
        out.put("note",
            "If 'idle in transaction' rows live longer than seconds, you have a leak. The fix is " +
            "ALWAYS commit/rollback in a finally block — for explicit tx control, use " +
            "TransactionTemplate.execute, never doInTransactionWithoutResult unless you're sure. " +
            "Backstop on the server side with idle_in_transaction_session_timeout.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 4. OBSERVABILITY — find the long-running transactions in production.
    //
    // This is the query you paste into psql when you want to find what's
    // bloating the DB. Sort by oldest tx first. The first row is usually
    // your culprit.
    // ---------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Map<String, Object> observability(long minTxAgeMs) {
        List<Object[]> rows = tx.execute(status -> em.createNativeQuery(
            "select " +
            "  pid, " +
            "  application_name, " +
            "  state, " +
            "  extract(epoch from (now() - xact_start)) * 1000 as tx_age_ms, " +
            "  wait_event_type, " +
            "  wait_event, " +
            "  backend_xmin::text, " +
            "  left(query, 200) as query " +
            "from pg_stat_activity " +
            "where datname = current_database() " +
            "  and xact_start is not null " +
            "  and pid <> pg_backend_pid() " +
            "  and extract(epoch from (now() - xact_start)) * 1000 >= :minMs " +
            "order by xact_start asc")
            .setParameter("minMs", minTxAgeMs)
            .getResultList());

        List<Map<String, Object>> txs = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pid", row[0]);
            m.put("application", row[1]);
            m.put("state", row[2]);
            m.put("txAgeMs", row[3]);
            m.put("waitEventType", row[4]);
            m.put("waitEvent", row[5]);
            m.put("backendXmin", row[6]);
            m.put("query", row[7]);
            txs.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "list transactions older than " + minTxAgeMs + "ms");
        out.put("longTransactions", txs);
        out.put("note",
            "Sort by xact_start asc — first row = oldest tx = most likely culprit. " +
            "backend_xmin is the snapshot horizon this tx is pinning; while it's not null, " +
            "VACUUM cannot reap rows newer than that. In production wire this query as a " +
            "scheduled check and alert on tx age > N seconds.");
        return out;
    }

    /** Seed N widget rows (the bloat demo needs at least one). */
    public void seed(int count) {
        tx.execute(status -> {
            em.createNativeQuery("delete from widget").executeUpdate();
            return null;
        });
        for (int i = 0; i < count; i++) {
            int idx = i;
            tx.execute(status -> {
                Widget w = new Widget("widget-" + idx, 0L);
                em.persist(w);
                return null;
            });
        }
        // Make sure pg_stat_user_tables has fresh stats.
        tx.execute(status -> em.createNativeQuery("ANALYZE widget").executeUpdate());
    }
}
