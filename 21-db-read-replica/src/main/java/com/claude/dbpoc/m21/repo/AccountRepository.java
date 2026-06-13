package com.claude.dbpoc.m21.repo;

import com.claude.dbpoc.m21.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {}
