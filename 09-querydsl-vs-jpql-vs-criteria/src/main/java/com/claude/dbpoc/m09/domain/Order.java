package com.claude.dbpoc.m09.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The entity every query in this module searches over. Indexes are listed
 * explicitly — the comparison is about the query *builder*, but the SQL it
 * produces still has to hit good indexes to be a fair comparison.
 */
@Entity
@Table(
    name = "orders",
    indexes = {
        @Index(name = "ix_orders_status", columnList = "status"),
        @Index(name = "ix_orders_customer", columnList = "customer_id"),
        @Index(name = "ix_orders_created_at", columnList = "created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * LAZY because the search projection joins the customer explicitly to
     * pull only the name — we never want Hibernate to issue a second SELECT
     * when the projection already has the data.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Lifecycle the front-end's status filter slices on. */
    public enum Status {
        NEW, PAID, SHIPPED, CANCELLED
    }
}
