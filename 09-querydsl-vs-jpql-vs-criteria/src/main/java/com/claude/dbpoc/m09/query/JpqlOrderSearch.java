package com.claude.dbpoc.m09.query;

import com.claude.dbpoc.m09.domain.OrderSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Trade-off this represents: <b>JPQL with StringBuilder</b>.
 *
 * What's good: low ceremony, the SQL is right there in the source, anyone on
 * the team can read it after a 5-minute primer. Named parameters are safe
 * from injection.
 *
 * What hurts: every filter is a string concat, every field is a string
 * literal. Rename {@code Order#status} and nothing complains until runtime
 * gives you {@code QuerySyntaxException}. Composing dynamic filters means
 * managing AND/WHERE state by hand — easy to ship a "WHERE WHERE" bug.
 *
 * Reach for this when the query is largely static and you want the SQL in
 * plain sight. Avoid for screens with five-plus optional filters.
 */
@Component
public class JpqlOrderSearch implements OrderSearch {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String name() { return "jpql"; }

    @Override
    public Page<OrderSummary> search(OrderSearchCriteria c, Pageable pageable) {
        // Two queries — the projection one and a matching COUNT(*) for the Page total.
        // We share the WHERE clause via a single buildWhere() so they cannot drift.
        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(c, params);

        // SELECT new ...OrderSummary(...) is the JPQL constructor-projection trick.
        // It forces Hibernate to materialize each row straight into the record
        // — no entity hydration, no first-level-cache churn.
        StringBuilder jpql = new StringBuilder()
            .append("SELECT new com.claude.dbpoc.m09.domain.OrderSummary(")
            .append("  o.id, c.name, o.status, o.amount, ")
            // Correlated sub-query for item count — keeps the main row width small
            // and side-steps any GROUP BY drama with pagination.
            .append("  (SELECT COUNT(i) FROM OrderItem i WHERE i.order = o), ")
            .append("  o.createdAt) ")
            .append("FROM Order o JOIN o.customer c ")
            .append(where)
            .append(buildOrderBy(pageable.getSort()));

        TypedQuery<OrderSummary> q = em.createQuery(jpql.toString(), OrderSummary.class);
        params.forEach(q::setParameter);
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());

        List<OrderSummary> results = q.getResultList();

        // Count query — same FROM + WHERE, no SELECT new, no ORDER BY.
        String countJpql = "SELECT COUNT(o) FROM Order o JOIN o.customer c " + where;
        Query countQuery = em.createQuery(countJpql);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        return new PageImpl<>(results, pageable, total);
    }

    /**
     * Hand-rolled WHERE composition. The {@code clauses} list keeps each
     * predicate independent so we never have to special-case "is this the
     * first one? then write WHERE, else AND". The {@code String.join(" AND ", clauses)}
     * at the end keeps the operator wiring in exactly one place.
     */
    private String buildWhere(OrderSearchCriteria c, Map<String, Object> params) {
        List<String> clauses = new ArrayList<>();

        c.customerNameLike().ifPresent(v -> {
            clauses.add("LOWER(c.name) LIKE LOWER(:custName)");
            params.put("custName", "%" + v + "%");
        });
        c.statuses().filter(s -> !s.isEmpty()).ifPresent(v -> {
            clauses.add("o.status IN (:statuses)");
            params.put("statuses", v);
        });
        c.from().ifPresent(v -> {
            clauses.add("o.createdAt >= :fromTs");
            params.put("fromTs", v);
        });
        c.to().ifPresent(v -> {
            clauses.add("o.createdAt < :toTs");
            params.put("toTs", v);
        });
        c.minAmount().ifPresent(v -> {
            clauses.add("o.amount >= :minAmt");
            params.put("minAmt", v);
        });
        c.maxAmount().ifPresent(v -> {
            clauses.add("o.amount <= :maxAmt");
            params.put("maxAmt", v);
        });
        c.country().ifPresent(v -> {
            clauses.add("c.country = :country");
            params.put("country", v);
        });

        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    /**
     * Map Pageable's Sort into JPQL ORDER BY. We whitelist field names — never
     * concatenate raw input into JPQL even though parameters are safe; sort
     * fields are *not* bind parameters and an attacker would happily inject
     * "id; DROP TABLE" if we trusted the request.
     */
    private String buildOrderBy(Sort sort) {
        if (sort.isUnsorted()) return " ORDER BY o.id DESC";
        StringBuilder sb = new StringBuilder(" ORDER BY ");
        boolean first = true;
        for (Sort.Order so : sort) {
            if (!first) sb.append(", ");
            first = false;
            String field = switch (so.getProperty()) {
                case "id"        -> "o.id";
                case "amount"    -> "o.amount";
                case "createdAt" -> "o.createdAt";
                case "status"    -> "o.status";
                default          -> "o.id";
            };
            sb.append(field).append(so.isAscending() ? " ASC" : " DESC");
        }
        return sb.toString();
    }
}
