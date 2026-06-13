package com.claude.dbpoc.m11.domain;

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
 * Subject of the lock-wait demos.
 *
 * The FOR UPDATE / NOWAIT / table-lock demos all operate on Account because
 * a money-balance row is the canonical "I MUST be the only writer for a
 * moment" scenario. Two concurrent callers race to lock the same account.
 *
 * Deliberately no @Version here — this module is about explicit
 * pessimistic locking primitives, not optimistic concurrency. The
 * @Version variant lives in module 13 (optimistic vs pessimistic).
 *
 * BigDecimal for balance: this is money. Using double would silently
 * round cents every other addition.
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
