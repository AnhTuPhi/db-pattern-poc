package com.claude.dbpoc.m05.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The "1" side of the N+1 problem.
 *
 * The {@code items} collection is LAZY on purpose — that's the entire setup:
 * a naive {@code findAll()} that then loops {@code o.getItems()} is what
 * triggers one extra SQL per parent.
 *
 * No @BatchSize annotation on the collection. The {@code /demo/batch-size}
 * endpoint instead sets {@code hibernate.default_batch_fetch_size} globally
 * (via the session-level hint) so the demo doesn't bake the fix into the
 * entity — the whole point is to compare fixes side-by-side.
 *
 * No @Cache on Order itself: the L2 cache demo focuses on the *child* Item
 * entity (the typical reference-data shape), which keeps the cache story
 * close to what teams actually do in production.
 */
@Entity
@Table(name = "orders")  // "order" is a reserved word in standard SQL.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String customerName;

    @Column(nullable = false)
    private Instant createdAt;

    /**
     * fetch = LAZY: required for the N+1 demo to exist at all.
     * Switching to EAGER would just trigger N+1 *every* time findAll() ran,
     * which is the same disease in a different costume.
     *
     * cascade = ALL + orphanRemoval = true so the /seed endpoint can persist
     * orders and their items in one save() call.
     */
    @OneToMany(mappedBy = "order",
               fetch = FetchType.LAZY,
               cascade = CascadeType.ALL,
               orphanRemoval = true)
    private List<Item> items = new ArrayList<>();

    /** Bidirectional sync helper used by the seeder. */
    public void addItem(Item item) {
        items.add(item);
        item.setOrder(this);
    }
}
