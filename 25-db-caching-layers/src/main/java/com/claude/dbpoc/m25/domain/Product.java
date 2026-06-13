package com.claude.dbpoc.m25.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "product")
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal price;

    public Product() {}

    public Product(String sku, String name, BigDecimal price) {
        this.sku = sku; this.name = name; this.price = price;
    }

    public Long getId()        { return id; }
    public String getSku()     { return sku; }
    public String getName()    { return name; }
    public BigDecimal getPrice() { return price; }

    public void setSku(String sku)         { this.sku = sku; }
    public void setName(String name)       { this.name = name; }
    public void setPrice(BigDecimal price) { this.price = price; }
}
