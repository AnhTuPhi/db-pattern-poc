package com.claude.dbpoc.m09.domain;

import com.claude.dbpoc.m09.domain.Order.Status;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The DTO every one of the four implementations returns. Kept as a record so
 * the JPQL "SELECT new ...OrderSummary(...)" projection and the QueryDSL
 * Projections.constructor(...) call have a stable shape to target.
 *
 * IMPORTANT: the constructor parameter order *is* the contract. Reorder it
 * and all four implementations have to follow — that's exactly the kind of
 * refactor pain the comparison table calls out.
 */
public record OrderSummary(
    Long orderId,
    String customerName,
    Status status,
    BigDecimal amount,
    Long itemCount,
    Instant createdAt
) {
}
