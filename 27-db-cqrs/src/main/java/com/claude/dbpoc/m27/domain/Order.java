package com.claude.dbpoc.m27.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Order() {}

    public Order(Long userId, BigDecimal total, String status) {
        this.userId = userId; this.total = total; this.status = status;
    }

    public Long getId()            { return id; }
    public Long getUserId()        { return userId; }
    public BigDecimal getTotal()   { return total; }
    public String getStatus()      { return status; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getUpdatedAt()  { return updatedAt; }

    public void setTotal(BigDecimal total) {
        this.total = total;
        this.updatedAt = Instant.now();
    }
    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }
}
