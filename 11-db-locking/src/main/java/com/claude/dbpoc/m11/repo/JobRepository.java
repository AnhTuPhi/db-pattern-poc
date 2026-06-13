package com.claude.dbpoc.m11.repo;

import com.claude.dbpoc.m11.domain.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Job repository — exposes the SKIP LOCKED dequeue, which is the
 * headline pattern of this module.
 *
 * The reason this is a native query and NOT @Lock(PESSIMISTIC_WRITE)
 * + LockOptions.SKIP_LOCKED:
 *   - JPA has no LockModeType for SKIP LOCKED. It's a Postgres/Oracle
 *     extension to FOR UPDATE.
 *   - Hibernate has a `jakarta.persistence.lock.timeout = -2` hint that
 *     emits SKIP LOCKED, but it's poorly documented and version-fragile.
 *     Native SQL is unambiguous: "this is the SQL that will run".
 *   - We want the reader of this code to recognise the exact statement
 *     they'd write by hand.
 */
public interface JobRepository extends JpaRepository<Job, Long> {

    /**
     * Atomically claim up to :n pending jobs. Two workers running this
     * concurrently will see DISJOINT result sets — SKIP LOCKED tells
     * Postgres "if a candidate row is already locked by another tx,
     * pretend you didn't see it; show me the next one".
     *
     * The FOR UPDATE locks the rows in the result for the duration of
     * the calling transaction. The caller is expected to mutate them
     * and commit promptly — long holds defeat the throughput argument
     * for using SKIP LOCKED in the first place.
     */
    @Query(
        value = "select * from job " +
                " where status = 'PENDING' " +
                " order by id " +
                " limit :n " +
                " for update skip locked",
        nativeQuery = true
    )
    List<Job> claimPending(@Param("n") int n);

    /**
     * Same shape as claimPending but with NOWAIT — used by callers who
     * want to fail fast if the queue head is already being processed
     * by someone else. Less common than SKIP LOCKED for queue work
     * (SKIP LOCKED is almost always what you want for a queue) but
     * included for completeness so the reader sees the difference.
     */
    @Query(
        value = "select * from job " +
                " where status = 'PENDING' " +
                " order by id " +
                " limit :n " +
                " for update nowait",
        nativeQuery = true
    )
    List<Job> claimPendingNowait(@Param("n") int n);
}
