package com.claude.dbpoc.m05.service;

import com.claude.dbpoc.common.SqlCounter;
import com.claude.dbpoc.m05.domain.Item;
import com.claude.dbpoc.m05.domain.Order;
import com.claude.dbpoc.m05.dto.DemoResult;
import com.claude.dbpoc.m05.dto.OrderSummaryDto;
import com.claude.dbpoc.m05.repo.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The six variants. Each method:
 *   1. resets the SqlCounter so we measure ONLY this variant's queries
 *   2. does the demo work (must touch items so lazy loading really runs)
 *   3. returns a DemoResult with the SQL count + elapsed time
 *
 * Why a service (not just methods on the controller)?
 *   @Transactional on a controller method works in Spring Boot, but bouncing
 *   through a separate bean makes the proxy boundary explicit — which matters
 *   when you debug "why is my @Transactional doing nothing" issues.
 *
 * All methods are @Transactional(readOnly = true) so lazy loading works
 * (we need an open session) without taking write locks.
 */
@Service
public class DemoService {

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private SqlCounter sqlCounter;

    @PersistenceContext
    private EntityManager em;

    // ------------------------------------------------------------------ //
    // VARIANT 1 — Naive. The disease.                                    //
    // ------------------------------------------------------------------ //
    /**
     * Expected SQL count: 1 + N
     *   1 query to load all orders, then one query per order to materialise
     *   its items the first time .getItems() is touched.
     */
    @Transactional(readOnly = true)
    public DemoResult naive() {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        List<Order> orders = orderRepo.findAll();
        long itemsTotal = 0;
        for (Order o : orders) {
            // Touching items() forces the lazy proxy to fire its SELECT.
            itemsTotal += o.getItems().stream().mapToInt(Item::getQuantity).sum();
        }

        return build("naive", orders.size(), itemsTotal, t0,
            "Classic N+1: 1 query for orders + 1 per order = 1 + N statements.");
    }

    // ------------------------------------------------------------------ //
    // VARIANT 2 — JOIN FETCH                                             //
    // ------------------------------------------------------------------ //
    /**
     * Expected SQL count: 1
     *   One outer join. DISTINCT in the JPQL dedupes parents Hibernate-side.
     *
     * Trade-off (commented on the repo method): cannot paginate this safely,
     * cannot stack multiple collection JOIN FETCHes without cartesian blowup.
     */
    @Transactional(readOnly = true)
    public DemoResult joinFetch() {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        List<Order> orders = orderRepo.findAllJoinFetch();
        long itemsTotal = orders.stream()
            .flatMap(o -> o.getItems().stream())
            .mapToInt(Item::getQuantity)
            .sum();

        return build("join-fetch", orders.size(), itemsTotal, t0,
            "Single JOIN FETCH. Watch out for cartesian blowup with 2+ collections.");
    }

    // ------------------------------------------------------------------ //
    // VARIANT 3 — EntityGraph                                            //
    // ------------------------------------------------------------------ //
    /**
     * Expected SQL count: 1
     *   Same physical plan as JOIN FETCH but the relationship is declared
     *   as data — cleaner for fixed graphs reused across queries.
     */
    @Transactional(readOnly = true)
    public DemoResult entityGraph() {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        List<Order> orders = orderRepo.findAllWithItemsGraph();
        long itemsTotal = orders.stream()
            .flatMap(o -> o.getItems().stream())
            .mapToInt(Item::getQuantity)
            .sum();

        return build("entity-graph", orders.size(), itemsTotal, t0,
            "@EntityGraph: declarative fetch plan. Cleanest fix for fixed relationships.");
    }

    // ------------------------------------------------------------------ //
    // VARIANT 4 — @BatchSize / default_batch_fetch_size                  //
    // ------------------------------------------------------------------ //
    /**
     * Expected SQL count: 1 + ceil(N / batchSize)
     *   The first SELECT loads parents. Touching items still triggers lazy
     *   loads, but Hibernate batches them into IN-clause queries grouped by
     *   {@code batchSize} parents at a time.
     *
     * We toggle this *per session* via Session#setProperty so the demo can
     * compare against the same entity without changing the @Entity mapping.
     * In production you'd usually set hibernate.default_batch_fetch_size
     * globally and forget about it — it's a reasonable default for any
     * lazy collection.
     */
    @Transactional(readOnly = true)
    public DemoResult batchSize(int batchSize) {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        Session session = em.unwrap(Session.class);
        session.setProperty("hibernate.default_batch_fetch_size", batchSize);

        List<Order> orders = orderRepo.findAll();
        long itemsTotal = 0;
        for (Order o : orders) {
            itemsTotal += o.getItems().stream().mapToInt(Item::getQuantity).sum();
        }

        return build("batch-size(" + batchSize + ")", orders.size(), itemsTotal, t0,
            "Batched lazy loads: ~1 + ceil(N/" + batchSize + "). Great default for unknown access patterns.");
    }

    // ------------------------------------------------------------------ //
    // VARIANT 5 — DTO projection                                         //
    // ------------------------------------------------------------------ //
    /**
     * Expected SQL count: 1
     *   Single aggregate SELECT, no entities materialised. Cannot trigger
     *   N+1 because there's no managed object to lazy-load *from*.
     */
    @Transactional(readOnly = true)
    public DemoResult dtoProjection() {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        List<OrderSummaryDto> summaries = orderRepo.findAllAsSummary();
        long itemsTotal = summaries.stream().mapToLong(OrderSummaryDto::getItemCount).sum();

        return build("dto-projection", summaries.size(), itemsTotal, t0,
            "Flat DTOs from one aggregate query. The only fix that *cannot* trigger N+1.");
    }

    // ------------------------------------------------------------------ //
    // VARIANT 6 — Second-level cache                                     //
    // ------------------------------------------------------------------ //
    /**
     * Two passes in one response.
     *   Pass 1: cold cache → 1 + N statements (same as naive).
     *   Pass 2: hot cache  → Hibernate hits the L2 region for items
     *           instead of issuing SELECTs. Typically drops to ~1 query
     *           (just the parent SELECT, which we did NOT mark @Cache).
     *
     * Pass 2 runs in its own transaction so the persistence context is fresh
     * — otherwise the first pass's already-loaded items would short-circuit
     * via the L1 (session) cache and we'd never see the L2 story.
     */
    public CachePassResult secondLevelCachePassOne() {
        return runCachePass("L2 cache (pass 1 — cold)");
    }

    public CachePassResult secondLevelCachePassTwo() {
        // Force a brand-new persistence context so the L1 cache cannot hide
        // the L2 cache's work.
        em.clear();
        return runCachePass("L2 cache (pass 2 — hot)");
    }

    @Transactional(readOnly = true)
    public CachePassResult runCachePass(String label) {
        sqlCounter.reset();
        long t0 = System.nanoTime();
        List<Order> orders = orderRepo.findAll();
        long itemsTotal = 0;
        for (Order o : orders) {
            itemsTotal += o.getItems().stream().mapToInt(Item::getQuantity).sum();
        }
        double elapsed = (System.nanoTime() - t0) / 1_000_000.0;
        return new CachePassResult(label, orders.size(), itemsTotal,
                                   sqlCounter.getStatementCount(), elapsed);
    }

    public record CachePassResult(String label, long ordersFetched, long itemsTotal,
                                  long sqlStatements, double elapsedMs) {}

    // ------------------------------------------------------------------ //
    // Helpers                                                            //
    // ------------------------------------------------------------------ //

    private DemoResult build(String variant, long orderCount, long itemsTotal,
                             long t0Nanos, String verdict) {
        double elapsed = (System.nanoTime() - t0Nanos) / 1_000_000.0;
        return new DemoResult(variant, orderCount, itemsTotal,
                              sqlCounter.getStatementCount(), elapsed, verdict);
    }
}
