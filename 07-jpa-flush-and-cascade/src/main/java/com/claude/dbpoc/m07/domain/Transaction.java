package com.claude.dbpoc.m07.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Leaf of the Customer → Account → Transaction graph.
 *
 * Used by FlushDemoController#autoTrigger — we issue
 * `SELECT FROM Transaction` after a pending Account INSERT to surface the
 * auto-flush behaviour. Postgres needs `transaction` quoted (reserved word)
 * but since we name the entity Transaction and explicitly @Table to "txn",
 * we sidestep that landmine entirely.
 */
@Entity
@Table(name = "txn")
@Getter
@Setter
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String description;

    private BigDecimal amount;

    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    public Transaction(String description, BigDecimal amount) {
        this.description = description;
        this.amount = amount;
        this.createdAt = Instant.now();
    }
}
