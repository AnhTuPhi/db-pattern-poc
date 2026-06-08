package com.claude.dbpoc.m07.repo;

import com.claude.dbpoc.m07.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
