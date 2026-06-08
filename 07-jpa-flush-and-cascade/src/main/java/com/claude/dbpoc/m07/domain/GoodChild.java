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
 * Owning side of GoodParent → GoodChild. Identical shape to BadChild —
 * the difference between the two trees is purely in the cascade attributes
 * on the parent's @OneToMany, which is the entire point.
 */
@Entity
@Table(name = "good_child")
@Getter
@Setter
@NoArgsConstructor
public class GoodChild {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private GoodParent parent;

    public GoodChild(String name) {
        this.name = name;
    }
}
