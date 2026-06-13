package com.claude.dbpoc.m12.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The deadlock canary. Each demo locks TWO Accounts in either order:
 *
 *   T1: lock A → lock B  (the money-transfer "from A to B" pattern)
 *   T2: lock B → lock A  (the money-transfer "from B to A" pattern)
 *
 * Without lock ordering, T1 holds A and waits for B, T2 holds B and waits
 * for A → deadlock. Postgres' deadlock_timeout (default 1s) detects the
 * cycle and aborts one with SQLSTATE 40P01.
 *
 * Lock ordering = always lock the LOWER id first, regardless of business
 * direction. That removes the cycle structurally — both transactions try
 * for the same row first, one waits, the other proceeds, no graph cycle.
 *
 * No @Version on purpose: this module is about explicit lock acquisition,
 * not optimistic concurrency.
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

    public Account(String owner, BigDecimal balance) {
        this.owner = owner;
        this.balance = balance;
    }
}
