package com.claude.dbpoc.m07.repo;

import com.claude.dbpoc.m07.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
}
