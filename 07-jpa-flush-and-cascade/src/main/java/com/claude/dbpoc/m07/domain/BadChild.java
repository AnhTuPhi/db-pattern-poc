package com.claude.dbpoc.m07.domain;

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
 * Owning side of the BadParent → BadChild relationship. The FK is on this
 * table (parent_id), so this is the side Hibernate looks at to determine
 * INSERT/UPDATE/DELETE order during flush.
 *
 * Plain LAZY @ManyToOne — fetch strategy isn't the lesson here; the cascade
 * surprise is.
 */
@Entity
@Table(name = "bad_child")
@Getter
@Setter
@NoArgsConstructor
public class BadChild {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private BadParent parent;

    public BadChild(String name) {
        this.name = name;
    }
}
