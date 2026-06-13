package com.claude.dbpoc.m29.repo;

import com.claude.dbpoc.m29.domain.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {
}
