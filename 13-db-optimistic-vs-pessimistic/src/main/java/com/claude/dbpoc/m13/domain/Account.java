package com.claude.dbpoc.m13.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The benchmark target. Same shape as m10's Account so the lessons stay
 * comparable; the difference is that here we drive it at high concurrency
 * to MEASURE the cost of each locking strategy, not to demonstrate the
 * anomaly.
 *
 * The @Version column powers the optimistic path: Hibernate adds
 *   AND version = ?
 * to every UPDATE's WHERE clause and bumps the version on success. When
 * two transactions both read version=N and both try to write back, the
 * second gets 0 rows affected and Hibernate throws
 * ObjectOptimisticLockingFailureException. That's the signal BenchService
 * catches and retries.
 *
 * BigDecimal for balance — see m10 for the "double on money is a fireable
 * offence" rationale. The benchmark adds $1 per iteration; using a string
 * literal "1" keeps the addition exact.
 */
@Entity
@Table(name = "account")
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String owner;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    /**
     * Optimistic-lock token. MUST be Long (boxed) so transient entities
     * are version=null and Hibernate treats them as new rather than
     * stale.
     */
    @Version
    private Long version;

    public Account(String owner, BigDecimal balance) {
        this.owner = owner;
        this.balance = balance;
    }
}
