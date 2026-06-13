package com.claude.dbpoc.m11.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The SKIP LOCKED canary. This is a job-queue row.
 *
 * The dequeue pattern is:
 *   SELECT * FROM job
 *    WHERE status = 'PENDING'
 *    ORDER BY id
 *    LIMIT :n
 *    FOR UPDATE SKIP LOCKED
 *
 * N workers run this concurrently. Each worker sees a DIFFERENT set of
 * rows because the FOR UPDATE locks each row as it scans, and SKIP
 * LOCKED tells the planner to skip any row another transaction already
 * has locked. Result: lossless, contention-free fanout with the DB as
 * the only coordinator.
 *
 * After successful processing the worker UPDATEs status='DONE'. If the
 * worker crashes mid-tx, Postgres releases the row lock at session
 * teardown and another worker picks it up on the next pass.
 *
 * lockedBy + lockedAt are advisory bookkeeping (which worker has it,
 * since when) — they are NOT the lock itself. The row-level FOR UPDATE
 * lock is what actually guarantees mutual exclusion.
 */
@Entity
@Table(name = "job")
@Getter
@Setter
@NoArgsConstructor
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 256)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    /**
     * Advisory: name of the worker that claimed this row. Useful for
     * monitoring "which worker is doing what" but NOT what enforces
     * mutual exclusion (the FOR UPDATE row lock does that).
     */
    @Column(length = 64)
    private String lockedBy;

    @Column
    private Instant lockedAt;

    public Job(String payload) {
        this.payload = payload;
        this.status = Status.PENDING;
    }

    public enum Status {
        PENDING,
        RUNNING,
        DONE
    }
}
