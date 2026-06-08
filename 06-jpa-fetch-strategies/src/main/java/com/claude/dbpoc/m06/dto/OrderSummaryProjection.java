package com.claude.dbpoc.m06.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Spring Data interface-based projection. Spring builds a proxy that backs
 * each method onto the underlying ResultSet (or a Tuple). Hibernate sees
 * the property list and generates a narrowed SELECT — only the columns
 * named below are fetched.
 *
 * Use case: read-only API responses where you want the convenience of
 * Spring deriving the query but don't want to drag the entity around.
 */
public interface OrderSummaryProjection {
    Long getOrderId();
    String getCustomerName();
    Instant getCreatedAt();
    BigDecimal getTotal();
}
