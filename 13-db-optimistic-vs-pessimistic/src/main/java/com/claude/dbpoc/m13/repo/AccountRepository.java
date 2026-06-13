package com.claude.dbpoc.m13.repo;

import com.claude.dbpoc.m13.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Three repository methods, one per benchmark strategy:
 *
 *   findById                 — used by the optimistic path. The plain read
 *                              loads version=N into the persistence context;
 *                              the UPDATE on flush adds  AND version = N
 *                              to its WHERE so a concurrent writer's update
 *                              will hit 0 rows and fail with OOL.
 *
 *   findByIdForUpdate        — used by the pessimistic path. Hibernate
 *                              issues SELECT ... FOR UPDATE on Postgres,
 *                              which takes a row-level write lock. Other
 *                              FOR UPDATE callers BLOCK until commit;
 *                              plain MVCC readers are unaffected. This is
 *                              the strategy that wins on a single hot row
 *                              because it converts the conflict-and-retry
 *                              dance into a clean queue.
 *
 *   addToBalance             — the CAS path. A single native UPDATE; no
 *                              read-then-write at the application layer.
 *                              Postgres serialises by row lock for the
 *                              duration of the statement, so concurrent
 *                              callers each succeed without any retries.
 *                              Only works when the new value can be
 *                              expressed as a SQL function of the old one.
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Pessimistic write lock. Translates to  SELECT ... FOR UPDATE  on
     * Postgres. The lock is released when the surrounding transaction
     * commits or rolls back. Concurrent FOR UPDATE callers wait in a
     * FIFO queue inside the engine.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * CAS-style increment in a single statement. Returns the number of
     * rows updated so the caller can assert the row existed (and detect
     * a bug like "wrong id"). Note we also bump the version manually so
     * any subsequent JPA-managed read of the same row stays consistent.
     */
    @Modifying
    @Query("update Account a set a.balance = a.balance + :delta, a.version = a.version + 1 where a.id = :id")
    int addToBalance(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
