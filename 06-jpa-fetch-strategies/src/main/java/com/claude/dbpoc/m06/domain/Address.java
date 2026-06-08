package com.claude.dbpoc.m06.domain;

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
 * Address — kept as a small, separate aggregate. LAZY on the back-reference
 * because there's no reason to pull Customer when you're iterating an
 * address book.
 *
 * This entity is shown as a "non-trap" example: the ManyToOne is correctly
 * marked LAZY. Compare with Order/OrderItem which leave it EAGER.
 */
@Entity
@Table(name = "address")
@Getter
@Setter
@NoArgsConstructor
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Correctly LAZY — this is the shape every ManyToOne in a real codebase should have.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(nullable = false)
    private String street;

    @Column(nullable = false)
    private String city;

    public Address(Customer customer, String street, String city) {
        this.customer = customer;
        this.street = street;
        this.city = city;
    }
}
