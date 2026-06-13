package com.claude.dbpoc.m27.repo;

import com.claude.dbpoc.m27.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {}
