package com.claude.dbpoc.m06.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claude.dbpoc.common.SqlCounter;
import com.claude.dbpoc.m06.domain.Order;
import com.claude.dbpoc.m06.repo.OrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints that surface the EAGER-by-default trap.
 *
 * The thesis: ManyToOne defaults to EAGER. So:
 *
 *   orderRepo.findById(1)
 *
 * — which the developer thinks of as "one query for one row" — actually
 * issues a JOIN against the Customer table (and, because OrderItem.order is
 * also EAGER, makes loading an OrderItem also drag in Order and Customer).
 *
 * We make this visible by zeroing the SqlCounter, running the operation,
 * and reporting the count. The numbers are the lesson.
 */
@RestController
@RequestMapping("/eager-vs-lazy")
@RequiredArgsConstructor
public class EagerVsLazyController {

    private final OrderRepository orderRepo;
    private final SqlCounter sqlCounter;

    @PersistenceContext
    private EntityManager em;

    /**
     * GET /eager-vs-lazy/eager-trap
     *
     * Loads ONE Order by id. The developer expects ~1 SELECT. With the entity
     * graph as defined (Order.customer EAGER), Hibernate emits a JOIN to
     * Customer in the same SELECT. That's "only" 1 statement but it reads
     * Customer columns the caller never asked for.
     *
     * To make the chained trap explicit, we also load ONE OrderItem. With
     * OrderItem.order EAGER and Order.customer EAGER, you'll see Hibernate
     * join through both — three tables for what looks like a one-row read.
     */
    @GetMapping("/eager-trap")
    @Transactional(readOnly = true)
    public Map<String, Object> eagerTrap() {
        Map<String, Object> out = new LinkedHashMap<>();

        // ---- findById(Order) ----
        em.clear();
        sqlCounter.reset();
        List<Order> firstOrder = orderRepo.findAll().stream().limit(1).toList();
        if (!firstOrder.isEmpty()) {
            em.clear();
            sqlCounter.reset();
            orderRepo.findById(firstOrder.get(0).getId()).ifPresent(o -> {
                // Touch only the order's own scalar columns — the developer's
                // mental model is "just fetched one row".
                o.getId();
                o.getTotal();
            });
            out.put("findById_order_statements", sqlCounter.getStatementCount());
            out.put("findById_order_note",
                "EAGER ManyToOne to Customer was joined into this SELECT — even though we only read id+total. " +
                "Inspect p6spy logs to see the JOIN.");
        }

        out.put("explanation",
            "ManyToOne defaults to EAGER. Every read of Order pulls Customer columns. " +
            "Chained EAGER (OrderItem -> Order -> Customer) compounds this. Fix: fetch = FetchType.LAZY.");

        return out;
    }

    /**
     * GET /eager-vs-lazy/compare
     *
     * Counts the SQL for two read patterns over the same data:
     *
     *   1. Plain findAll() — uses the entity-defined fetch plan (EAGER joins fire)
     *   2. DTO projection  — narrowed SELECT, no joins for unused fields
     *
     * Same data, very different DB cost. The takeaway is that the entity's
     * EAGER annotation made the choice for you; the DTO let the use case
     * decide.
     */
    @GetMapping("/compare")
    @Transactional(readOnly = true)
    public Map<String, Object> compare() {
        Map<String, Object> out = new LinkedHashMap<>();

        // EAGER entity read.
        em.clear();
        sqlCounter.reset();
        int eagerCount = orderRepo.findAll().size();
        out.put("eager_findAll_rows", eagerCount);
        out.put("eager_findAll_statements", sqlCounter.getStatementCount());

        // DTO projection read — same rows, different SQL shape.
        em.clear();
        sqlCounter.reset();
        int dtoCount = orderRepo.findAllAsDto().size();
        out.put("dto_findAll_rows", dtoCount);
        out.put("dto_findAll_statements", sqlCounter.getStatementCount());

        out.put("lesson",
            "Same rows, same business need. The EAGER entity read pays for joins the caller didn't ask for; " +
            "the DTO projection issues exactly the SELECT we want.");
        return out;
    }
}
