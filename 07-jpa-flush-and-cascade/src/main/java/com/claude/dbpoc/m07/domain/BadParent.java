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
 * The "trap" entity. Demonstrates both flavours of the cascade foot-gun at once:
 *
 *   cascade = ALL          → every operation propagates, including REMOVE.
 *                            em.remove(parent) deletes every child without you
 *                            ever calling em.remove on them.
 *
 *   orphanRemoval = true   → if a child is detached from the collection (either
 *                            by remove(child) or — much worse — by the entire
 *                            collection reference being replaced), Hibernate
 *                            issues DELETE statements for the orphans on flush.
 *
 * Pairing the two is the most common production setup that goes wrong: someone
 * cleans up a parent, every child evaporates, audit / referencing tables break.
 */
@Entity
@Table(name = "bad_parent")
@Getter
@Setter
@NoArgsConstructor
public class BadParent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    // mappedBy="parent" → BadChild owns the FK; this side is the inverse.
    // cascade=ALL + orphanRemoval=true is the textbook unsafe combo.
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BadChild> children = new ArrayList<>();

    public BadParent(String name) {
        this.name = name;
    }

    /** Helper used by Seed/Cascade controllers — keeps the back-reference set. */
    public void addChild(BadChild child) {
        children.add(child);
        child.setParent(this);
    }
}
