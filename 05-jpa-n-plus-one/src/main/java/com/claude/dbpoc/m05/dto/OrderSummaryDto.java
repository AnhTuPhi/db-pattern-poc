package com.claude.dbpoc.m05.dto;

/**
 * Projection target for the JPQL {@code SELECT new ...} constructor expression.
 *
 * Public, top-level, constructor-matched: those three are required for JPQL
 * to instantiate it. Using a record instead of a class is fine for DTOs
 * (records are top-level and have a canonical constructor), but a class
 * makes the constructor parameter order explicit and gives Lombok-free
 * readability.
 *
 * Why DTOs are the *only* fix that can't trigger N+1: there's no association
 * traversal possible on a non-entity object — the data is flattened to
 * primitives at the SQL level, so Hibernate physically cannot issue a
 * follow-up query for a missing relation.
 */
public class OrderSummaryDto {

    private final Long id;
    private final String customerName;
    private final long itemCount;
    private final Double total;

    public OrderSummaryDto(Long id, String customerName, long itemCount, Double total) {
        this.id = id;
        this.customerName = customerName;
        this.itemCount = itemCount;
        this.total = total == null ? 0.0 : total;
    }

    public Long getId() { return id; }
    public String getCustomerName() { return customerName; }
    public long getItemCount() { return itemCount; }
    public Double getTotal() { return total; }
}
