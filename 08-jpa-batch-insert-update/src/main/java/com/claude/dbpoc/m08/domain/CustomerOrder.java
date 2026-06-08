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
 * Second entity used purely by the {@code /write-order/demo} endpoint. The
 * point of having two entity types in the same transaction is to demonstrate
 * how {@code hibernate.order_inserts}/{@code order_updates} group writes by
 * entity type so they can batch.
 *
 * Without ordering: insert(Customer) insert(Order) insert(Customer)
 * insert(Order) ... → each transition between types FLUSHES the current batch
 * and starts a new one. With ordering: all Customer inserts together, then all
 * Order inserts together → 2 batches total (one per type).
 */
@Entity
@Table(name = "customer_order")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_customer_order_gen")
    @SequenceGenerator(
        name = "seq_customer_order_gen",
        sequenceName = "seq_customer_order",
        allocationSize = 50
    )
    private Long id;

    @Column(nullable = false)
    private Long customerId; // deliberately loose — we want to write both kinds freely

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant placedAt;
}
