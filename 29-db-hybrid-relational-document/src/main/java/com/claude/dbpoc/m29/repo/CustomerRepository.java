package com.claude.dbpoc.m29.repo;

import com.claude.dbpoc.m29.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    List<Customer> findAllByTenantId(Long tenantId);
}
