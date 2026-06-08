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
 * The "safe" counterpart. Same shape as BadParent but with the explicit-
 * cascade pattern we actually recommend:
 *
 *   cascade = {PERSIST, MERGE}
 *     - PERSIST so saving a parent saves freshly-built children with it
 *       (the actually-useful piece of cascade).
 *     - MERGE so detached graphs reattach cleanly. Almost never causes
 *       a surprise on its own.
 *
 *   No REMOVE. No orphanRemoval.
 *     - Deleting a parent fails (FK from child) unless the caller has
 *       already removed the children. That refusal IS the safety: forces
 *       you to think about what should happen to the children.
 *
 * The cost of safety here is one line of explicit cleanup in the service.
 * The cost of cascade=ALL is "we lost the audit history" in a postmortem.
 */
@Entity
@Table(name = "good_parent")
@Getter
@Setter
@NoArgsConstructor
public class GoodParent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @OneToMany(mappedBy = "parent", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private List<GoodChild> children = new ArrayList<>();

    public GoodParent(String name) {
        this.name = name;
    }

    public void addChild(GoodChild child) {
        children.add(child);
        child.setParent(this);
    }
}
