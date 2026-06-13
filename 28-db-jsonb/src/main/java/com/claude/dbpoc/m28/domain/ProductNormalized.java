package com.claude.dbpoc.m28.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The boring relational shape: one column per field. Constraints are
 * real (NOT NULL, CHECK on stock), indexes are obvious (brand, category),
 * queries are textbook SQL. This is the row we want to beat with JSONB
 * — and on the workloads where the shape is known up-front, we can't.
 */
@Entity
@Table(name = "product_normalized")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductNormalized {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String brand;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer stock;

    @Column(nullable = false)
    private String category;
}
