package com.claude.dbpoc.m06.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.claude.dbpoc.m06.domain.OrderItem;

/**
 * Plain repo — used to show the chained-EAGER trap (loading one OrderItem
 * eagerly pulls Order, which eagerly pulls Customer).
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
