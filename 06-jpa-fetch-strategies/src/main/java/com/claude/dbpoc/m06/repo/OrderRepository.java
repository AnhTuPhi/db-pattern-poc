package com.claude.dbpoc.m06.repo;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.Tuple;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.claude.dbpoc.m06.domain.Order;
import com.claude.dbpoc.m06.dto.OrderSummaryDto;
import com.claude.dbpoc.m06.dto.OrderSummaryProjection;

/**
 * Order repository — three "shapes" of read are demonstrated here so the
 * caller can see the SQL cost of each:
 *
 *   1. Plain findById / findAll
 *        -> EAGER joins fire because of the entity annotations
 *
 *   2. @EntityGraph variants
 *        -> caller controls the fetch plan WITHOUT changing the entity
 *
 *   3. DTO / Tuple / projection variants
 *        -> Hibernate emits a narrow SELECT, no proxies, no LIE risk
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    // ----- plain (entity) reads ---------------------------------------------

    // Inherited findById / findAll already demonstrate the EAGER trap because
    // of Order.customer being EAGER. No extra method needed.

    // ----- @EntityGraph: the caller-side fix that doesn't touch the entity --

    /**
     * Same as findAll(), but tells Hibernate "make 'customer' part of this
     * fetch plan", so it issues a single SELECT with a LEFT JOIN instead of
     * the second-query lazy load pattern. We list "customer" explicitly so
     * the EntityGraph behaviour stays visible in p6spy.
     */
    @EntityGraph(attributePaths = {"customer"})
    @Query("SELECT o FROM Order o")
    List<Order> findAllWithCustomer();

    /**
     * Caller wants the items too. EntityGraph spans multiple attributes.
     *
     * WARNING: do not paginate this — fetching a collection + applying LIMIT
     * triggers the dreaded HHH000104 "firstResult/maxResults specified with
     * collection fetch; applying in memory" warning, which silently loads
     * the entire result set and paginates in app memory.
     */
    @EntityGraph(attributePaths = {"customer", "items"})
    @Query("SELECT DISTINCT o FROM Order o")
    List<Order> findAllWithCustomerAndItems();

    // ----- JOIN FETCH alternative (explicit JPQL) ---------------------------

    /**
     * The "old-school" join-fetch. Equivalent to the EntityGraph above but
     * the fetch shape is written directly in JPQL. Useful when you want a
     * named query in one place rather than annotation-based.
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(Long id);

    // ----- DTO projection: the production-grade default --------------------

    /**
     * Constructor expression. Hibernate emits a narrow SELECT (only the
     * columns named in the constructor are read). The result is a record,
     * not a proxy, so:
     *   - no LazyInitializationException possible
     *   - no risk of an N+1 in the serialiser
     *   - the SQL is exactly what you'd write by hand
     */
    @Query("""
        SELECT new com.claude.dbpoc.m06.dto.OrderSummaryDto(
            o.id, c.name, o.createdAt, o.total
        )
        FROM Order o JOIN o.customer c
        """)
    List<OrderSummaryDto> findAllAsDto();

    /**
     * Spring Data interface-based projection. Same result shape as the DTO,
     * different mechanism — Spring builds the proxy at runtime from the
     * Tuple results. Slightly more magic, slightly less ceremony.
     */
    @Query("""
        SELECT o.id AS orderId, c.name AS customerName,
               o.createdAt AS createdAt, o.total AS total
        FROM Order o JOIN o.customer c
        """)
    List<OrderSummaryProjection> findAllAsInterfaceProjection();

    /**
     * Raw javax.persistence.Tuple. Useful when the projection is dynamic
     * (you don't have a known DTO at compile time), e.g. report builders.
     */
    @Query("""
        SELECT o.id AS orderId, c.name AS customerName, o.total AS total
        FROM Order o JOIN o.customer c
        """)
    List<Tuple> findAllAsTuple();
}
