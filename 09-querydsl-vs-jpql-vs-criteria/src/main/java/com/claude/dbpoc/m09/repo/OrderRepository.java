package com.claude.dbpoc.m09.repo;

import com.claude.dbpoc.m09.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
