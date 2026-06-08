package com.claude.dbpoc.m08.repo;

import com.claude.dbpoc.m08.domain.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {
}
