package com.claude.dbpoc.m06.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.claude.dbpoc.m06.domain.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
