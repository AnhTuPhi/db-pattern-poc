package com.claude.dbpoc.m06.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Order entity — deliberately wired with EAGER on ManyToOne to demonstrate
 * the most common trap in JPA.
 *
 * TODO[FETCH-TRAP]: customer is @ManyToOne(fetch = EAGER). This is the JPA
 * default for ManyToOne and almost always wrong. Every findById(Order)
 * silently issues a JOIN (or a follow-up SELECT) to load the Customer, even
 * when the caller only needs the order's id+total. Across a request that
 * touches 100 orders you've now done 100 wasted joins or 100 extra SELECTs.
 *
 * The fix is one annotation: fetch = FetchType.LAZY. The lesson is that
 * EAGER is contagious — it doesn't ask permission of the caller, it just
 * happens. See /eager-trap to see this in the SQL log.
 */
@Entity
@Table(name = "orders") // "order" is reserved in SQL
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // TODO[FETCH-TRAP]: ManyToOne defaults to EAGER. Chained EAGER causes
    // large joins on every findById(Order). Always set to LAZY.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    // Collection side stays LAZY — items can grow large.
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    public Order(Customer customer, Instant createdAt, BigDecimal total) {
        this.customer = customer;
        this.createdAt = createdAt;
        this.total = total;
    }
}
