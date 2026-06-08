package com.claude.dbpoc.m08.repo;

import com.claude.dbpoc.m08.domain.IdentityCustomer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityCustomerRepository extends JpaRepository<IdentityCustomer, Long> {
}
