package com.claude.dbpoc.m10.repo;

import com.claude.dbpoc.m10.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Account repository — adds two pessimistic-lock variants on top of the
 * default findById/save:
 *
 *   findByIdForUpdate — JPA-managed pessimistic write lock. Hibernate
 *     issues SELECT ... FOR UPDATE on Postgres. Concurrent readers using
 *     plain findById() are NOT blocked (MVCC); only other writers /
 *     other FOR UPDATE callers wait.
 *
 *   findByIdForUpdateSkipLocked — native SQL. Used in the "queue
 *     worker" pattern: if a row is already locked, skip it and try the
 *     next one. Not strictly needed for the lost-update demo but very
 *     useful real-world primitive — included so the reader sees it.
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Standard JPA pessimistic write lock. Hibernate translates this to
     * SELECT ... FOR UPDATE on Postgres. Other transactions doing the
     * same WILL block until this transaction commits or rolls back.
     * That blocking is what prevents lost updates in
     * LostUpdateService.lostUpdateSelectForUpdate.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * "Take this row if free, otherwise skip me — don't block." Standard
     * pattern for worker pools dequeueing from a jobs table. Native SQL
     * because JPQL doesn't have a SKIP LOCKED clause and the @Lock
     * annotation alone can't express it.
     */
    @Query(
        value = "select * from account where id = :id for update skip locked",
        nativeQuery = true
    )
    Optional<Account> findByIdForUpdateSkipLocked(@Param("id") Long id);
}
