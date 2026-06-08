package com.claude.dbpoc.m08.repo;

import com.claude.dbpoc.m08.domain.AssignedCustomer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignedCustomerRepository extends JpaRepository<AssignedCustomer, String> {
}
