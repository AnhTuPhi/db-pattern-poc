package com.claude.dbpoc.m08.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The anti-pattern. {@link GenerationType#IDENTITY} forces Hibernate to call
 * {@code Statement.getGeneratedKeys()} after every single insert to retrieve
 * the just-assigned PK — and that requirement DISABLES JDBC batching for
 * inserts of this entity on most databases (Postgres, MySQL, etc).
 *
 * Concretely: even with {@code hibernate.jdbc.batch_size=50}, you will see
 * 10,000 single-row INSERTs in p6spy when you saveAll() 10,000 of these.
 *
 * Hibernate logs this with a clear warning at startup:
 *   "HHH000069: ... disabling insert batching"
 *
 * Oracle 12c+ does support batching with IDENTITY in some configurations, but
 * the portable rule is: if you want bulk inserts, do not use IDENTITY.
 */
@Entity
@Table(name = "identity_customer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdentityCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
