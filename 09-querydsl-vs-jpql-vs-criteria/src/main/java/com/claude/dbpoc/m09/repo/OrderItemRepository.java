package com.claude.dbpoc.m09.repo;

import com.claude.dbpoc.m09.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
