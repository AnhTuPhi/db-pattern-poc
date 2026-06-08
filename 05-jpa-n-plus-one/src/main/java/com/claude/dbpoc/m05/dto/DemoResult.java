package com.claude.dbpoc.m05.dto;

/**
 * The one-line answer that every /demo/* endpoint returns. The point of this
 * POC is the {@code sqlStatements} number — that's the column to compare
 * across variants.
 */
public record DemoResult(
    String variant,
    long ordersFetched,
    long itemsTotal,
    long sqlStatements,
    double elapsedMs,
    String verdict
) {}
