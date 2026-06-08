package com.claude.dbpoc.m09.query;

import com.claude.dbpoc.m09.domain.Customer;
import com.claude.dbpoc.m09.domain.Order;
import com.claude.dbpoc.m09.domain.OrderItem;
import com.claude.dbpoc.m09.domain.OrderSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

// NOTE: jakarta.persistence.criteria.Order *the JPA class* clashes with our own
//       com.claude.dbpoc.m09.domain.Order entity. We import the entity and
//       refer to the JPA sort-Order via its fully-qualified name at the call
//       site (cb.asc(...)/cb.desc(...) hide it most of the time anyway).

/**
 * Trade-off this represents: <b>JPA Criteria API</b>.
 *
 * What's good: type-checked at compile time (Path, Predicate), JPA-spec
 * standard so nothing extra on the classpath, the same builder constructs
 * both the SELECT and the COUNT query without string duplication.
 *
 * What hurts: famously verbose. Every column reference is a method call,
 * every join needs a separate Root for the count query (can't reuse the
 * one above), and reading the code top-to-bottom does not look like the
 * SQL it produces. Refactor a field name and you're safer than JPQL — but
 * only if you use the generated JPA metamodel (which doubles the build setup).
 * Without the metamodel, the {@code .get("name")} strings are no safer than
 * JPQL.
 *
 * Reach for this when: you already have the spec-only constraint and you're
 * willing to pay the verbosity tax for compile-time safety without adding
 * QueryDSL to the classpath.
 */
@Component
public class CriteriaOrderSearch implements OrderSearch {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String name() { return "criteria"; }

    @Override
    public Page<OrderSummary> search(OrderSearchCriteria c, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        // ---- the main SELECT (projection) ------------------------------------
        CriteriaQuery<OrderSummary> cq = cb.createQuery(OrderSummary.class);
        Root<Order> o = cq.from(Order.class);
        // INNER join — every order has a customer.
        Join<Order, Customer> cust = o.join("customer");

        // Correlated sub-query for the per-order item count.
        Subquery<Long> itemCount = cq.subquery(Long.class);
        Root<OrderItem> oi = itemCount.from(OrderItem.class);
        itemCount.select(cb.count(oi))
                 .where(cb.equal(oi.get("order"), o));

        // construct(...) is the Criteria equivalent of "SELECT new ...(...)".
        // The argument order MUST line up with the OrderSummary record constructor.
        cq.select(cb.construct(
            OrderSummary.class,
            o.get("id"),
            cust.get("name"),
            o.get("status"),
            o.get("amount"),
            itemCount.getSelection(),
            o.get("createdAt")
        ));

        // Dynamic WHERE — accumulate into a Predicate[] then AND them.
        List<Predicate> preds = buildPredicates(cb, o, cust, c);
        if (!preds.isEmpty()) {
            cq.where(cb.and(preds.toArray(new Predicate[0])));
        }

        // ORDER BY from Pageable — same whitelist idea as JpqlOrderSearch.
        cq.orderBy(buildOrderBy(cb, o, pageable.getSort()));

        TypedQuery<OrderSummary> tq = em.createQuery(cq);
        tq.setFirstResult((int) pageable.getOffset());
        tq.setMaxResults(pageable.getPageSize());
        List<OrderSummary> results = tq.getResultList();

        // ---- the COUNT query -------------------------------------------------
        // Criteria insists every query has its own Root tree — we cannot reuse
        // the SELECT's roots. This duplication is the single biggest reason
        // Criteria pages of code grow longer than their JPQL equivalents.
        CriteriaQuery<Long> countCq = cb.createQuery(Long.class);
        Root<Order> oc = countCq.from(Order.class);
        Join<Order, Customer> custC = oc.join("customer");
        countCq.select(cb.count(oc));
        List<Predicate> countPreds = buildPredicates(cb, oc, custC, c);
        if (!countPreds.isEmpty()) {
            countCq.where(cb.and(countPreds.toArray(new Predicate[0])));
        }
        long total = em.createQuery(countCq).getSingleResult();

        return new PageImpl<>(results, pageable, total);
    }

    /**
     * Predicate factory. Parameterised by the {@code o} and {@code cust} roots
     * so we can call it once for SELECT and once for COUNT — Criteria forces
     * us to rebuild the predicate tree against different roots.
     */
    private List<Predicate> buildPredicates(CriteriaBuilder cb,
                                            Root<Order> o,
                                            Join<Order, Customer> cust,
                                            OrderSearchCriteria c) {
        List<Predicate> preds = new ArrayList<>();

        c.customerNameLike().ifPresent(v ->
            preds.add(cb.like(cb.lower(cust.get("name")), "%" + v.toLowerCase() + "%")));
        c.statuses().filter(s -> !s.isEmpty()).ifPresent(v ->
            preds.add(o.get("status").in(v)));
        c.from().ifPresent(v ->
            preds.add(cb.greaterThanOrEqualTo(o.get("createdAt"), v)));
        c.to().ifPresent(v ->
            preds.add(cb.lessThan(o.get("createdAt"), v)));
        c.minAmount().ifPresent(v ->
            preds.add(cb.greaterThanOrEqualTo(o.get("amount"), v)));
        c.maxAmount().ifPresent(v ->
            preds.add(cb.lessThanOrEqualTo(o.get("amount"), v)));
        c.country().ifPresent(v ->
            preds.add(cb.equal(cust.get("country"), v)));

        return preds;
    }

    private List<jakarta.persistence.criteria.Order> buildOrderBy(CriteriaBuilder cb,
                                                                  Root<Order> o,
                                                                  Sort sort) {
        List<jakarta.persistence.criteria.Order> orders = new ArrayList<>();
        if (sort.isUnsorted()) {
            orders.add(cb.desc(o.get("id")));
            return orders;
        }
        for (Sort.Order so : sort) {
            Path<?> path = switch (so.getProperty()) {
                case "amount"    -> o.get("amount");
                case "createdAt" -> o.get("createdAt");
                case "status"    -> o.get("status");
                default          -> o.get("id");
            };
            orders.add(so.isAscending() ? cb.asc(path) : cb.desc(path));
        }
        return orders;
    }
}
