package com.claude.dbpoc.m10.domain;

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
import java.time.Instant;

/**
 * The phantom-read canary.
 *
 * The PhantomReadService runs:
 *   SELECT count(*) FROM transfer WHERE amount > 100
 * twice in the same transaction, with another thread INSERTing a matching
 * row in between. On READ_COMMITTED the counts differ. On Postgres
 * REPEATABLE_READ they don't — snapshot isolation prevents the phantom
 * even though the SQL standard would allow it.
 *
 * Deliberately denormalised — no FK to Account — because we want to be
 * able to INSERT freely without worrying about whether the demo seeded
 * matching accounts.
 */
@Entity
@Table(name = "transfer_log")
@Getter
@Setter
@NoArgsConstructor
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long fromAccountId;

    @Column(nullable = false)
    private Long toAccountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant createdAt;

    public Transfer(Long fromAccountId, Long toAccountId, BigDecimal amount) {
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.createdAt = Instant.now();
    }
}
