package com.claude.dbpoc.m09.query;

import com.claude.dbpoc.m09.domain.OrderSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The contract every one of the four implementations satisfies, so the
 * /compare endpoint can call them through a single type and the JSON it
 * returns is genuinely apples-to-apples.
 */
public interface OrderSearch {

    /** Stable label that ends up in the JSON response so callers can tell who answered. */
    String name();

    Page<OrderSummary> search(OrderSearchCriteria criteria, Pageable pageable);
}
