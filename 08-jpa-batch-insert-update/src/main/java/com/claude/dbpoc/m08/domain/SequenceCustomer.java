package com.claude.dbpoc.m08.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The "right way to PK in JPA for bulk loads". {@link GenerationType#SEQUENCE}
 * lets Hibernate allocate a block of IDs up front, so it knows every new
 * entity's PK without round-tripping after the insert — meaning JDBC batching
 * works end to end.
 *
 * The {@link SequenceGenerator#allocationSize} of 50 means Hibernate calls the
 * sequence ONCE per 50 inserts (using Hibernate's "pooled" optimiser, the
 * sequence physically increments by 50 each call). With batch_size=50 the
 * sequence round-trips drop to 2% of the insert count.
 *
 * RULE OF THUMB: allocationSize should be >= hibernate.jdbc.batch_size.
 * If they're equal you get one sequence call per batch — minimum overhead.
 *
 * Postgres requires the sequence to be created with {@code INCREMENT BY 50}
 * to match — when using ddl-auto: create-drop, Hibernate handles this. In
 * production schemas you must keep DB-side INCREMENT in sync with allocationSize
 * or risk PK collisions.
 */
@Entity
@Table(name = "sequence_customer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SequenceCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_customer_gen")
    @SequenceGenerator(
        name = "seq_customer_gen",
        sequenceName = "seq_customer",
        allocationSize = 50
    )
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;
}
