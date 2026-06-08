package com.claude.dbpoc.m09.query;

import com.claude.dbpoc.m09.domain.Order.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Search input shared by all four implementations. Optional<T> is deliberate:
 * the dynamic-query problem is exactly "which filters are present this call".
 *
 * The whole comparison hinges on how cleanly each builder handles the matrix
 * of "this one but not those two" inputs — 2^7 = 128 combinations from this
 * record alone.
 */
public record OrderSearchCriteria(
    Optional<String>          customerNameLike,
    Optional<Set<Status>>     statuses,
    Optional<Instant>         from,
    Optional<Instant>         to,
    Optional<BigDecimal>      minAmount,
    Optional<BigDecimal>      maxAmount,
    Optional<String>          country
) {
    /** Empty constructor convenience for the controllers that build from query params. */
    public static OrderSearchCriteria empty() {
        return new OrderSearchCriteria(
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());
    }
}
