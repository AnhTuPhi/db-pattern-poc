package com.claude.dbpoc.m05.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

/**
 * The "N" side of the N+1 problem.
 *
 * @Cache: enrols Item in Hibernate's L2 cache. The /demo/second-level-cache
 * endpoint calls findAll() twice and shows the SQL count drops on the second
 * pass — Hibernate serves the items out of the cache region instead of issuing
 * one SELECT per parent.
 *
 * NONSTRICT_READ_WRITE: cheap, doesn't take locks. Correct for reference data
 * where occasional staleness is acceptable. For mutation-heavy entities use
 * READ_WRITE (uses soft locks) or TRANSACTIONAL (XA).
 *
 * region = "items" — names the cache region so we can size/configure it
 * separately if needed (matches what production setups usually do).
 */
@Entity
@Table(name = "items")
@Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE, region = "items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * fetch = LAZY on @ManyToOne: the default since JPA 2.1 was EAGER, which
     * is one of the biggest footguns in JPA. Explicitly LAZY here means we
     * never accidentally load the parent Order back when reading an Item.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    private String productName;
    private int quantity;
    private double unitPrice;
}
