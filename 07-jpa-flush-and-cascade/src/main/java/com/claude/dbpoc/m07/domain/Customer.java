package com.claude.dbpoc.m07.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Realistic-graph aggregate root for the dirty-checking demos.
 *
 * Customer 1—* Account 1—* Transaction
 *
 * We load the whole pyramid into one Hibernate session in
 * /dirty-check/large to demonstrate the O(session size) cost of the
 * flush autoflush. PERSIST cascade only — the dirty-check demos never
 * delete, so we don't need REMOVE / orphan semantics here, and leaving
 * them off keeps this entity safe to extend.
 */
@Entity
@Table(name = "customer")
@Getter
@Setter
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** Mutated by /dirty-check/* — single-field change to trigger the dirty pass. */
    private String email;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.PERSIST)
    private List<Account> accounts = new ArrayList<>();

    public Customer(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public void addAccount(Account account) {
        accounts.add(account);
        account.setCustomer(this);
    }
}
