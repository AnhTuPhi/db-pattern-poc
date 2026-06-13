package com.claude.dbpoc.m14.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * A boring row. We update it repeatedly to generate dead tuples that VACUUM
 * would normally reap — but won't, if a long transaction is pinning the
 * xmin horizon. That's the bloat demo.
 */
@Entity
public class Widget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Long version;       // not @Version — just a counter we increment

    public Widget() {}

    public Widget(String name, Long version) {
        this.name = name;
        this.version = version;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Long getVersion() { return version; }
    public void setName(String name) { this.name = name; }
    public void setVersion(Long v) { this.version = v; }
}
