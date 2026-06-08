package com.claude.dbpoc.m10.domain;

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
 * The fintech canary. Every isolation demo touches this entity.
 *
 * Why @Version exists here:
 *   The lostUpdateOptimisticVersion demo uses Hibernate's optimistic
 *   locking. On UPDATE, Hibernate adds  AND version = ?  to the WHERE
 *   clause and bumps the version. If two threads both read version=1
 *   and both try to write back with version=1 in the WHERE, the second
 *   gets zero rows affected and Hibernate throws
 *   ObjectOptimisticLockingFailureException. That's the bug-detector.
 *
 *   Cost: every transactional write to Account becomes
 *   UPDATE account SET balance=?, version=? WHERE id=? AND version=?
 *   — one extra column in the WHERE, one extra in the SET. Tiny.
 *
 * Why BigDecimal: this represents money. double would lose cents on every
 * other addition. In a fintech context using double for money is a fireable
 * offence (literally — see the Knight Capital flash-crash postmortems).
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
     * The optimistic-lock token. Hibernate manages this automatically:
     *   - reads it into memory on SELECT
     *   - puts the read value in the UPDATE's WHERE clause
     *   - bumps it on every successful UPDATE
     * Make this a Long (not long) so a fresh transient entity has version=null
     * and Hibernate knows to treat it as "new", not "stale".
     */
    @Version
    private Long version;

    public Account(String owner, BigDecimal balance) {
        this.owner = owner;
        this.balance = balance;
    }
}
