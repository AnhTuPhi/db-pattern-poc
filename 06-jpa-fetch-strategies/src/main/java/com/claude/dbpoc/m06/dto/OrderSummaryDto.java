package com.claude.dbpoc.m06.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for the "read for display" use case. Used as the target type of:
 *
 *   - JPQL constructor expressions:  SELECT new com.claude.dbpoc.m06.dto.OrderSummaryDto(o.id, ...)
 *   - QueryDSL / native query mapping
 *
 * Key property: NO references to entities. Once you map into this record
 * there are no proxies, so there's no LazyInitializationException to worry
 * about, no session lifetime to manage, and no surprise SELECTs in the view.
 */
public record OrderSummaryDto(
    Long orderId,
    String customerName,
    Instant createdAt,
    BigDecimal total
) {}
