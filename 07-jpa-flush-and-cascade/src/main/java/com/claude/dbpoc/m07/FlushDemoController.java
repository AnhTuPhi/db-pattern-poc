package com.claude.dbpoc.m07;

import com.claude.dbpoc.common.SqlCounter;
import com.claude.dbpoc.m07.domain.Account;
import com.claude.dbpoc.m07.domain.Customer;
import com.claude.dbpoc.m07.repo.CustomerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flush behaviour, made visible.
 *
 * The thesis: most JPA developers cannot tell you when their changes
 * actually reach the database. The default — FlushMode.AUTO — flushes
 * before any query that *might* read the modified entity. That's the
 * line every "I thought my UPDATE ran" bug hides behind.
 *
 * Each endpoint pins down one specific behaviour and surfaces the SQL
 * count + the order Hibernate chose. Compare endpoint outputs to read
 * the rule directly off the data.
 */
@RestController
@RequestMapping("/flush")
public class FlushDemoController {

    private final CustomerRepository customerRepo;
    private final SqlCounter sqlCounter;

    @PersistenceContext
    private EntityManager em;

    public FlushDemoController(CustomerRepository customerRepo, SqlCounter sqlCounter) {
        this.customerRepo = customerRepo;
        this.sqlCounter = sqlCounter;
    }

    // ---------------------------------------------------------------------
    // /flush/auto-trigger
    //   FlushMode.AUTO (the default). A pending INSERT is forced to disk
    //   the moment a query touches the same table. Surprises devs who
    //   think "but I didn't call flush()".
    // ---------------------------------------------------------------------
    @PostMapping("/auto-trigger")
    @Transactional
    public Map<String, Object> autoFlushTrigger() {
        sqlCounter.reset();

        Customer c = new Customer("auto-flush-target", "auto@example.com");
        em.persist(c);
        long sqlAfterPersist = sqlCounter.getStatementCount();
        // No INSERT has fired yet. persist() only assigns an ID via the
        // identity generator and queues the row for the next flush.

        // The query touches the same entity type. AUTO mode flushes
        // pending writes to keep the result set consistent with what's
        // queued in the session.
        Long count = em.createQuery("SELECT COUNT(c) FROM Customer c", Long.class).getSingleResult();
        long sqlAfterQuery = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flushMode", em.getFlushMode().toString());
        out.put("sqlAfterPersist", sqlAfterPersist);
        out.put("sqlAfterQuery", sqlAfterQuery);
        out.put("statementsAddedByQuery", sqlAfterQuery - sqlAfterPersist);
        out.put("customerCount", count);
        out.put("lesson",
                "Persist queued the INSERT but didn't run it. The COUNT query forced an auto-flush — " +
                "the INSERT ran first so the query saw consistent data. statementsAddedByQuery includes both.");
        return out;
    }

    // ---------------------------------------------------------------------
    // /flush/commit-mode
    //   FlushMode.COMMIT defers writes until commit. The query above no
    //   longer triggers a flush — the COUNT will be the pre-INSERT value.
    //   Faster, but the query result lies relative to in-memory state.
    // ---------------------------------------------------------------------
    @PostMapping("/commit-mode")
    @Transactional
    public Map<String, Object> commitFlushMode() {
        sqlCounter.reset();

        // First, capture the baseline so we can prove the COUNT did NOT
        // include the new row.
        Long before = em.createQuery("SELECT COUNT(c) FROM Customer c", Long.class).getSingleResult();

        em.setFlushMode(FlushModeType.COMMIT);

        Customer c = new Customer("commit-mode-target", "commit@example.com");
        em.persist(c);

        sqlCounter.reset();
        Long duringTx = em.createQuery("SELECT COUNT(c) FROM Customer c", Long.class).getSingleResult();
        long sqlForQuery = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flushMode", em.getFlushMode().toString());
        out.put("countBefore", before);
        out.put("countDuringTxAfterPersist", duringTx);
        out.put("sqlForQuery", sqlForQuery);
        out.put("lesson",
                "FlushMode.COMMIT skipped the flush. The COUNT query ran straight against the DB, " +
                "missing the in-memory persist. Saves a write round-trip; costs you read consistency " +
                "with your own session. The INSERT will still hit the DB at commit.");
        return out;
    }

    // ---------------------------------------------------------------------
    // /flush/explicit
    //   em.flush() — the developer takes the wheel. Useful when you need
    //   a generated ID *now* (e.g. to use in a subsequent native query)
    //   or to surface a constraint violation early.
    // ---------------------------------------------------------------------
    @PostMapping("/explicit")
    @Transactional
    public Map<String, Object> explicitFlush() {
        sqlCounter.reset();

        Customer c = new Customer("explicit-flush", "explicit@example.com");
        em.persist(c);
        long sqlAfterPersist = sqlCounter.getStatementCount();

        em.flush();
        long sqlAfterFlush = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idBeforeFlush", c.getId() == null ? "null (depends on generator)" : c.getId());
        out.put("idAfterFlush", c.getId());
        out.put("sqlAfterPersist", sqlAfterPersist);
        out.put("sqlAfterFlush", sqlAfterFlush);
        out.put("statementsRunByFlush", sqlAfterFlush - sqlAfterPersist);
        out.put("lesson",
                "em.flush() forced the pending INSERT to disk synchronously. Use it when you need " +
                "the assigned identity for a follow-up query, or to surface a unique-constraint " +
                "violation inside the try block instead of at commit.");
        return out;
    }

    // ---------------------------------------------------------------------
    // /flush/no-flush-for-unrelated
    //   AUTO is *smarter* than "flush on any query". It only flushes if
    //   the query could possibly read the dirty entity type. A query
    //   against an unrelated table goes through with the writes still
    //   pending.
    // ---------------------------------------------------------------------
    @PostMapping("/no-flush-for-unrelated")
    @Transactional
    public Map<String, Object> noFlushForUnrelated() {
        sqlCounter.reset();

        Customer c = new Customer("unrelated-marker", "unrelated@example.com");
        em.persist(c);
        long sqlAfterPersist = sqlCounter.getStatementCount();

        // Native COUNT against a different table — Hibernate cannot prove
        // the result depends on Customer, so no flush is triggered.
        Number accountCount = (Number) em.createNativeQuery("SELECT COUNT(*) FROM account").getSingleResult();
        long sqlAfterQuery = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sqlAfterPersist", sqlAfterPersist);
        out.put("sqlAfterQuery", sqlAfterQuery);
        out.put("accountCount", accountCount);
        out.put("lesson",
                "Native query on `account` doesn't reference Customer, so AUTO did not flush. " +
                "The INSERT is still queued. With NATIVE queries Hibernate plays it safer — " +
                "for native queries against the SAME table it will still flush, but the " +
                "table-reachability check is conservative.");
        return out;
    }

    // ---------------------------------------------------------------------
    // /flush/ordering
    //   hibernate.order_inserts=true (set in application.yml). All Customer
    //   INSERTs are grouped together, then all Account INSERTs. This is
    //   what unlocks JDBC batching — without ordering, the batch resets
    //   every time you switch entity types.
    // ---------------------------------------------------------------------
    @PostMapping("/ordering")
    @Transactional
    public Map<String, Object> insertOrdering() {
        sqlCounter.reset();

        // Interleave creations: customer, account, customer, account, ...
        // hibernate.order_inserts will re-sort the flush into:
        // INSERT INTO customer (...) VALUES (...)  -- batched
        // INSERT INTO account  (...) VALUES (...)  -- batched
        for (int i = 0; i < 5; i++) {
            Customer c = new Customer("ord-customer-" + i, "ord" + i + "@example.com");
            Account a = new Account("ORD-" + i, BigDecimal.valueOf(100 * (i + 1)));
            c.addAccount(a);
            em.persist(c);
        }

        em.flush();
        long sqlAfterFlush = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entitiesPersisted", 10);
        out.put("sqlStatements", sqlAfterFlush);
        out.put("lesson",
                "Interleaved persist of 5 customers + 5 accounts. With order_inserts=true and " +
                "jdbc.batch_size>=N, Hibernate groups INSERTs by entity type before flushing — " +
                "two batches instead of ten round-trips. Module 08 covers the batching story end-to-end.");
        return out;
    }
}
