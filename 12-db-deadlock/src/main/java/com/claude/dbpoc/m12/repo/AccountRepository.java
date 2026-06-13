package com.claude.dbpoc.m12.repo;

import com.claude.dbpoc.m12.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Just one repository method — JPA-managed pessimistic write lock.
 * Hibernate appends FOR UPDATE on Postgres. Two concurrent callers of
 * this method on the SAME id form the deadlock setup when each call has
 * the OPPOSITE next-lock-to-take.
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
