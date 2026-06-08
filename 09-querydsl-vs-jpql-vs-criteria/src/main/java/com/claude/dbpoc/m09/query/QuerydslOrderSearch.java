package com.claude.dbpoc.m09.query;

import com.claude.dbpoc.m09.domain.OrderSummary;
import com.claude.dbpoc.m09.domain.QCustomer;
import com.claude.dbpoc.m09.domain.QOrder;
import com.claude.dbpoc.m09.domain.QOrderItem;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Trade-off this represents: <b>QueryDSL</b>.
 *
 * What's good: fully type-safe (the Q-classes are regenerated when you rename
 * a field — the compiler points at every break), short and readable, the
 * dynamic-filter pattern (BooleanBuilder) reads exactly like the matrix of
 * "if filter present, AND in clause". The same factory builds the SELECT
 * and the COUNT query without rebuilding the join tree.
 *
 * What hurts: extra dependencies, extra build plumbing (the APT processor
 * has to run before the IDE knows about QOrder), and one more thing to teach
 * a new joiner.
 *
 * Reach for this when: dynamic-filter list/search screens, anything where
 * the query shape varies by call. Default choice for new projects in this
 * repo — see README.
 *
 * If you see "cannot resolve QOrder" in your IDE, run:
 *     mvn -pl 09-querydsl-vs-jpql-vs-criteria compile
 * and reimport. The Q-classes land in target/generated-sources/annotations.
 */
@Component
public class QuerydslOrderSearch implements OrderSearch {

    private final JPAQueryFactory qf;

    public QuerydslOrderSearch(JPAQueryFactory qf) {
        this.qf = qf;
    }

    @Override
    public String name() { return "querydsl"; }

    @Override
    public Page<OrderSummary> search(OrderSearchCriteria c, Pageable pageable) {
        QOrder o = QOrder.order;
        QCustomer cust = QCustomer.customer;
        QOrderItem oi = QOrderItem.orderItem;

        BooleanBuilder where = new BooleanBuilder();
        c.customerNameLike().ifPresent(v -> where.and(cust.name.lower().like("%" + v.toLowerCase() + "%")));
        c.statuses().filter(s -> !s.isEmpty()).ifPresent(v -> where.and(o.status.in(v)));
        c.from().ifPresent(v -> where.and(o.createdAt.goe(v)));
        c.to().ifPresent(v -> where.and(o.createdAt.lt(v)));
        c.minAmount().ifPresent(v -> where.and(o.amount.goe(v)));
        c.maxAmount().ifPresent(v -> where.and(o.amount.loe(v)));
        c.country().ifPresent(v -> where.and(cust.country.eq(v)));

        // Projections.constructor(...) is the QueryDSL spelling of "SELECT new ...(...)".
        // Type-checked: a wrong-arity constructor here fails at *compile* time.
        JPAQuery<OrderSummary> q = qf.select(Projections.constructor(
                OrderSummary.class,
                o.id,
                cust.name,
                o.status,
                o.amount,
                JPAExpressions.select(oi.count()).from(oi).where(oi.order.eq(o)),
                o.createdAt))
            .from(o)
            .join(o.customer, cust)
            .where(where);

        for (OrderSpecifier<?> os : buildOrderSpecifiers(o, pageable.getSort())) {
            q.orderBy(os);
        }

        List<OrderSummary> results = q
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

        // Reusing the same where Predicate for the count keeps the two queries
        // literally synchronized — the headline win over Criteria.
        Long total = qf.select(o.count())
            .from(o)
            .join(o.customer, cust)
            .where(where)
            .fetchOne();

        return new PageImpl<>(results, pageable, total == null ? 0L : total);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<OrderSpecifier<?>> buildOrderSpecifiers(QOrder o, Sort sort) {
        List<OrderSpecifier<?>> out = new ArrayList<>();
        if (sort.isUnsorted()) {
            out.add(o.id.desc());
            return out;
        }
        for (Sort.Order so : sort) {
            ComparableExpressionBase<?> path = switch (so.getProperty()) {
                case "amount"    -> o.amount;
                case "createdAt" -> o.createdAt;
                case "status"    -> o.status;
                default          -> o.id;
            };
            out.add(so.isAscending() ? path.asc() : path.desc());
        }
        return out;
    }
}
