package com.claude.dbpoc.m05.repo;

import com.claude.dbpoc.m05.domain.Order;
import com.claude.dbpoc.m05.dto.OrderSummaryDto;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Every N+1 fix lives as its own method here so the contrast is clear in one file.
 *
 * Inherited from JpaRepository:
 *   - findAll() — the naive fetch that triggers N+1 once the caller touches items.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * FIX 1 — JOIN FETCH.
     *
     * One SQL: a single SELECT with an outer join that materialises orders +
     * items in a single result set, then Hibernate de-duplicates parents.
     *
     * Caveats baked into this query:
     *  - SELECT DISTINCT: required because joining a one-to-many returns one
     *    row per child, so each parent appears N times in the raw result set.
     *    DISTINCT here is a Hibernate hint to dedupe entities (it does NOT
     *    add a DISTINCT keyword to the SQL when Hibernate sees a collection
     *    JOIN FETCH in Hibernate 6+).
     *  - DO NOT add Pageable to this method — paginating a join-fetched
     *    collection forces Hibernate into in-memory pagination (HHH000104
     *    warning) which loads the whole table.
     *  - DO NOT add a second JOIN FETCH for another collection — that yields
     *    a cartesian product (MultipleBagFetchException in some configs).
     */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items")
    List<Order> findAllJoinFetch();

    /**
     * FIX 2 — @EntityGraph.
     *
     * Same physical SQL as JOIN FETCH but declarative: the relationship to load
     * is data, not part of the JPQL string. Wins when the graph is fixed and
     * you want to reuse the same finder for both "with items" and "without".
     *
     * The "type = LOAD" default means: load these attributes eagerly, keep
     * everything else at its mapped default. Use FETCH to override eager
     * defaults if you've inherited a codebase with too many EAGER mappings.
     */
    @EntityGraph(attributePaths = "items")
    @Query("SELECT o FROM Order o")
    List<Order> findAllWithItemsGraph();

    /**
     * FIX 3 — DTO projection.
     *
     * Bypasses entity loading entirely: Hibernate runs ONE aggregate SQL
     * against the join and instantiates DTOs row-by-row. Cannot trigger N+1
     * because there are no entities to lazy-load from.
     *
     * Best for read-heavy paths (list views, exports, dashboards).
     */
    @Query("""
        SELECT new com.claude.dbpoc.m05.dto.OrderSummaryDto(
            o.id, o.customerName, COUNT(i.id), SUM(i.quantity * i.unitPrice))
        FROM Order o LEFT JOIN o.items i
        GROUP BY o.id, o.customerName
        """)
    List<OrderSummaryDto> findAllAsSummary();
}
