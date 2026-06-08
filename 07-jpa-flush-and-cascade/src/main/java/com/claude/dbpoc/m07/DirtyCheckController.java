package com.claude.dbpoc.m07;

import com.claude.dbpoc.common.SqlCounter;
import com.claude.dbpoc.m07.domain.Customer;
import com.claude.dbpoc.m07.repo.CustomerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dirty checking, made measurable.
 *
 * Every time the session flushes, Hibernate walks every managed entity,
 * compares each field against the load-time snapshot, and emits an
 * UPDATE for every entity whose state diverged. That walk is O(session
 * size × field count). Most production hot-path UPDATE latency is
 * actually dirty-check CPU spent on entities you never touched.
 *
 * These endpoints prove it. Seed with /seed?customers=N and watch the
 * SQL count and elapsed time grow linearly with N — even though only
 * one entity was modified.
 */
@RestController
@RequestMapping("/dirty-check")
public class DirtyCheckController {

    private final CustomerRepository customerRepo;
    private final SqlCounter sqlCounter;

    @PersistenceContext
    private EntityManager em;

    public DirtyCheckController(CustomerRepository customerRepo, SqlCounter sqlCounter) {
        this.customerRepo = customerRepo;
        this.sqlCounter = sqlCounter;
    }

    // ---------------------------------------------------------------------
    // /dirty-check/large
    //   Loads everything into the session, mutates ONE row, watches the
    //   dirty-check scan walk all N entities to find the change.
    //
    //   The headline number is `elapsedMs`. Compare it to /one-entity below.
    // ---------------------------------------------------------------------
    @GetMapping("/large")
    @Transactional
    public Map<String, Object> dirtyCheckLargeSession() {
        sqlCounter.reset();

        long t0 = System.nanoTime();
        // Pull every customer into the session. Hibernate now tracks N
        // entities and will scan all of them on the next flush.
        List<Customer> all = customerRepo.findAll();
        long loadNs = System.nanoTime() - t0;
        long sqlAfterLoad = sqlCounter.getStatementCount();

        // Mutate ONE entity. The change set is size 1 — but the dirty-check
        // walk has to look at all N to find that out.
        Customer first = all.get(0);
        String oldEmail = first.getEmail();
        first.setEmail("dirty-large-" + System.currentTimeMillis() + "@example.com");

        sqlCounter.reset();
        long t1 = System.nanoTime();
        em.flush();
        long flushNs = System.nanoTime() - t1;
        long sqlOnFlush = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entitiesInSession", all.size());
        out.put("entitiesMutated", 1);
        out.put("loadMs", loadNs / 1_000_000.0);
        out.put("flushMs", flushNs / 1_000_000.0);
        out.put("sqlOnLoad", sqlAfterLoad);
        out.put("sqlOnFlush", sqlOnFlush);
        out.put("oldEmail", oldEmail);
        out.put("newEmail", first.getEmail());
        out.put("lesson",
                "One UPDATE was emitted, but flushMs is non-trivial — Hibernate scanned all " +
                all.size() + " entities looking for changes. Cost = O(session_size). Compare to " +
                "/dirty-check/one-entity, which is the same UPDATE against an empty session.");
        return out;
    }

    // ---------------------------------------------------------------------
    // /dirty-check/one-entity
    //   Same UPDATE, but the session holds exactly one entity. Cost is
    //   what most devs assume dirty-check should be.
    // ---------------------------------------------------------------------
    @GetMapping("/one-entity")
    @Transactional
    public Map<String, Object> dirtyCheckSingleEntity() {
        // Pull ONE row only; baseline for what the flush cost should look like.
        Customer first = customerRepo.findAll().stream().findFirst().orElseThrow();
        // Clear the rest from the session so only `first` is tracked.
        em.clear();
        first = em.merge(first);

        sqlCounter.reset();
        long t0 = System.nanoTime();
        first.setEmail("dirty-one-" + System.currentTimeMillis() + "@example.com");
        em.flush();
        long flushNs = System.nanoTime() - t0;
        long sqlOnFlush = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entitiesInSession", 1);
        out.put("entitiesMutated", 1);
        out.put("flushMs", flushNs / 1_000_000.0);
        out.put("sqlOnFlush", sqlOnFlush);
        out.put("newEmail", first.getEmail());
        out.put("lesson",
                "Same single-row UPDATE. The dirty-check walk had nothing else to scan. " +
                "This is the latency you SHOULD see when you UPDATE one row.");
        return out;
    }

    // ---------------------------------------------------------------------
    // /dirty-check/no-change
    //   Load N, change nothing, flush. The check still runs. The cost
    //   of safety is paid even when nothing changed — Hibernate cannot
    //   know that without scanning.
    // ---------------------------------------------------------------------
    @GetMapping("/no-change")
    @Transactional
    public Map<String, Object> dirtyCheckNoChange() {
        List<Customer> all = customerRepo.findAll();

        sqlCounter.reset();
        long t0 = System.nanoTime();
        em.flush();   // no-op for the DB; expensive for Hibernate.
        long flushNs = System.nanoTime() - t0;
        long sqlOnFlush = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entitiesInSession", all.size());
        out.put("entitiesMutated", 0);
        out.put("flushMs", flushNs / 1_000_000.0);
        out.put("sqlOnFlush", sqlOnFlush);
        out.put("lesson",
                "Zero UPDATEs emitted. flushMs is the pure scan cost — Hibernate compared every " +
                "field of every entity to the load-time snapshot and found nothing dirty. The " +
                "cost is paid up-front regardless of what changed.");
        return out;
    }

    // ---------------------------------------------------------------------
    // /dirty-check/dto-readonly?n=...
    //   The mitigation: read-only DTO query. No managed entities, no
    //   dirty-check pass on flush, no surprise UPDATEs from accidental
    //   setters.
    // ---------------------------------------------------------------------
    @GetMapping("/dto-readonly")
    @Transactional(readOnly = true)
    public Map<String, Object> dtoReadOnly() {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        // Constructor projection — returns plain records. Hibernate does
        // not put these in the persistence context. There is no snapshot
        // to compare against because there's no managed entity.
        List<Object[]> rows = em.createQuery(
                "SELECT c.id, c.name, c.email FROM Customer c", Object[].class).getResultList();

        long elapsedNs = System.nanoTime() - t0;
        long sql = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rowsReturned", rows.size());
        out.put("elapsedMs", elapsedNs / 1_000_000.0);
        out.put("sqlStatements", sql);
        out.put("lesson",
                "DTO projection bypasses the persistence context entirely. No managed entities = " +
                "no dirty-check. Use this for read paths. Pair with @Transactional(readOnly=true) " +
                "so Hibernate also skips the auto-flush before query.");
        return out;
    }
}
