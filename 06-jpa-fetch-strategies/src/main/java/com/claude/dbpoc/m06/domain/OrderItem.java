package com.claude.dbpoc.m06.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
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

/**
 * OrderItem — the second link in the EAGER-cascading-fetch chain.
 *
 * TODO[FETCH-TRAP]: order is @ManyToOne(fetch = EAGER). Combined with
 * Order.customer being EAGER, this creates the classic chained-eager trap:
 *
 *     loading one OrderItem -> joins Order -> joins Customer
 *
 * So orderItemRepo.findById(1) returns one row but actually fetches three
 * tables. Multiply by request volume and you're paying for joins nobody
 * asked for. See /eager-trap for the SQL evidence.
 *
 * Fix: fetch = FetchType.LAZY on both ManyToOne sides.
 */
@Entity
@Table(name = "order_item")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // TODO[FETCH-TRAP]: chained EAGER. Loading an OrderItem will eager-join
    // Order -> Customer (because Order.customer is also EAGER). Set to LAZY.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(nullable = false)
    private String product;

    @Column(nullable = false)
    private int qty;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    public OrderItem(Order order, String product, int qty, BigDecimal price) {
        this.order = order;
        this.product = product;
        this.qty = qty;
        this.price = price;
    }
}
