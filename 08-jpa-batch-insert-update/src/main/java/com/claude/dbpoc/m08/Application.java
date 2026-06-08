package com.claude.dbpoc.m08;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Module 08 — JPA bulk insert/update: why it's often 50-100x slower than it
 * should be, and the exact knobs to fix it.
 *
 * Boots a tiny REST service on :8208. The headline endpoint is
 * POST /bench?n=10000 — runs every variant in sequence (jdbc baseline,
 * IDENTITY anti-pattern, SEQUENCE with/without batching, assigned UUID,
 * sequence with allocationSize=100, and on Oracle the CACHE vs NOCACHE
 * sequence comparison) and returns a JSON table sorted by elapsed time.
 *
 * Two profiles:
 *   - postgres (default) — uses ?reWriteBatchedInserts=true
 *   - oracle             — adds the sequence CACHE/NOCACHE variant
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
