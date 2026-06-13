package com.claude.dbpoc.m11.repo;

import com.claude.dbpoc.m11.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Account repository.
 *
 * Exposes the JPA-managed pessimistic write lock (plain FOR UPDATE) as
 * findByIdForUpdate, plus a native SQL variant with the NOWAIT clause
 * for the fail-fast demo.
 *
 * Why both:
 *   - JPA's @Lock(PESSIMISTIC_WRITE) → "SELECT ... FOR UPDATE" — waits.
 *   - JPA has no portable way to express NOWAIT — it's a Postgres/Oracle
 *     extension. We have to drop to native SQL for it.
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Plain pessimistic write lock. Hibernate appends FOR UPDATE on
     * Postgres. Concurrent callers of this same method BLOCK until the
     * holding transaction commits or rolls back. Plain readers (no FOR
     * UPDATE) are NOT blocked — Postgres MVCC means a concurrent
     * findById() returns the last-committed snapshot regardless.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * FOR UPDATE NOWAIT. If another transaction already holds a
     * conflicting row lock, Postgres immediately raises:
     *   ERROR: could not obtain lock on row in relation "account"
     *   SQLSTATE 55P03 (lock_not_available)
     * No wait, no timeout — the caller fails in microseconds. Perfect
     * for "if I can't get the lock right now, fail the request and let
     * the client retry" REST handlers where you'd rather return 409
     * than tie up a request thread.
     *
     * Native SQL because the JPA spec has no LockModeType for NOWAIT.
     * Hibernate has a non-portable timeout hint (jakarta.persistence
     * .lock.timeout = 0) but native is more explicit and unambiguous.
     */
    @Query(
        value = "select * from account where id = :id for update nowait",
        nativeQuery = true
    )
    Optional<Account> findByIdForUpdateNowait(@Param("id") Long id);
}
