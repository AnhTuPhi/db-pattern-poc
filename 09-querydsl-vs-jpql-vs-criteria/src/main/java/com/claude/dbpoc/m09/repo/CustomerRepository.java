package com.claude.dbpoc.m09.repo;

import com.claude.dbpoc.m09.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
