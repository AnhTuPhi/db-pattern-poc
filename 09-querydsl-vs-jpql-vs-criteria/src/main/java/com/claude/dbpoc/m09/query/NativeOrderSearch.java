package com.claude.dbpoc.m09.query;

import com.claude.dbpoc.m09.domain.Order.Status;
import com.claude.dbpoc.m09.domain.OrderSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trade-off this represents: <b>native SQL</b>.
 *
 * What's good: you write the SQL you want. Postgres-specific features
 * (LATERAL, FILTER, window functions, JSONB operators) are available
 * verbatim. The DBA can paste the query into psql to EXPLAIN it without
 * translation.
 *
 * What hurts: zero protection against renamed columns. The result-set
 * mapping is manual (Object[] indices) or via @SqlResultSetMapping (more
 * ceremony). Truly dynamic WHERE is back to StringBuilder + parameter map,
 * with the added risk that you can't lean on JPA escaping for identifiers.
 *
 * Reach for this when: the query needs DB-specific features that JPQL /
 * QueryDSL won't express (CTEs, window functions, GIN-trigram operators,
 * vendor-specific hints). Or when the DBA owns the query and JPA is just
 * the conveyance.
 *
 * In this module the native variant is the reference: every other
 * implementation should produce SQL that's structurally identical to this.
 */
@Component
public class NativeOrderSearch implements OrderSearch {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String name() { return "native"; }

    @Override
    public Page<OrderSummary> search(OrderSearchCriteria c, Pageable pageable) {
        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(c, params);

        // Correlated sub-query keeps the row width tight — same shape as JPQL.
        // Column aliases match the OrderSummary constructor argument order so
        // the mapping below is positional and easy to follow.
        String sql = """
            SELECT
                o.id                                                 AS order_id,
                c.name                                               AS customer_name,
                o.status                                             AS status,
                o.amount                                             AS amount,
                (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.id) AS item_count,
                o.created_at                                         AS created_at
            FROM orders o
            JOIN customers c ON c.id = o.customer_id
            """ + where +
            buildOrderBy(pageable.getSort()) +
            " LIMIT :pageSize OFFSET :pageOffset";

        Query q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        q.setParameter("pageSize", pageable.getPageSize());
        q.setParameter("pageOffset", pageable.getOffset());

        @SuppressWarnings("unchecked")
        List<Object[]> raw = q.getResultList();
        List<OrderSummary> results = raw.stream().map(NativeOrderSearch::toSummary).toList();

        // COUNT query — same FROM + WHERE, no projection, no ORDER BY, no paging.
        String countSql = "SELECT COUNT(*) FROM orders o JOIN customers c ON c.id = o.customer_id " + where;
        Query countQ = em.createNativeQuery(countSql);
        params.forEach(countQ::setParameter);
        long total = ((Number) countQ.getSingleResult()).longValue();

        return new PageImpl<>(results, pageable, total);
    }

    private static OrderSummary toSummary(Object[] row) {
        // Defensive type coercion — JDBC returns Number subclasses, Timestamp
        // for instant columns, etc. Keep this code obvious so future readers
        // can trace any "ClassCastException" back to one line.
        Long id           = ((Number) row[0]).longValue();
        String custName   = (String) row[1];
        Status status     = Status.valueOf((String) row[2]);
        BigDecimal amount = (BigDecimal) row[3];
        Long itemCount    = ((Number) row[4]).longValue();
        Instant createdAt = row[5] instanceof Timestamp ts ? ts.toInstant() : ((Instant) row[5]);
        return new OrderSummary(id, custName, status, amount, itemCount, createdAt);
    }

    private String buildWhere(OrderSearchCriteria c, Map<String, Object> params) {
        List<String> clauses = new ArrayList<>();

        c.customerNameLike().ifPresent(v -> {
            clauses.add("LOWER(c.name) LIKE LOWER(:custName)");
            params.put("custName", "%" + v + "%");
        });
        c.statuses().filter(s -> !s.isEmpty()).ifPresent(v -> {
            clauses.add("o.status IN (:statuses)");
            // Native queries want String values for the enum (column is varchar).
            params.put("statuses", v.stream().map(Enum::name).toList());
        });
        c.from().ifPresent(v -> {
            clauses.add("o.created_at >= :fromTs");
            params.put("fromTs", Timestamp.from(v));
        });
        c.to().ifPresent(v -> {
            clauses.add("o.created_at < :toTs");
            params.put("toTs", Timestamp.from(v));
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
     * Whitelist the sort column. Sort fields are not bind parameters and
     * concatenating raw input is one of the easiest SQL-injection routes
     * if you forget. (See JpqlOrderSearch#buildOrderBy for the same logic.)
     */
    private String buildOrderBy(Sort sort) {
        if (sort.isUnsorted()) return " ORDER BY o.id DESC";
        StringBuilder sb = new StringBuilder(" ORDER BY ");
        boolean first = true;
        for (Sort.Order so : sort) {
            if (!first) sb.append(", ");
            first = false;
            String col = switch (so.getProperty()) {
                case "amount"    -> "o.amount";
                case "createdAt" -> "o.created_at";
                case "status"    -> "o.status";
                default          -> "o.id";
            };
            sb.append(col).append(so.isAscending() ? " ASC" : " DESC");
        }
        return sb.toString();
    }
}
