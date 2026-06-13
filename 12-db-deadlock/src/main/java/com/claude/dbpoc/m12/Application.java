package com.claude.dbpoc.m12;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Module 12 — db-deadlock.
 *
 * Run with:    mvn -pl 12-db-deadlock spring-boot:run
 * Postgres:    bring up via ../scripts/setup.sh postgres
 *
 * Thesis: deadlock is not a database bug — it is the database catching YOUR
 * bug. Two transactions wait on each other's locks; the engine detects the
 * cycle in the wait-for graph and kills the loser with SQLSTATE 40P01.
 * The fix is almost never "retry harder" — it's lock-ordering: every
 * transaction that acquires N locks does so in the SAME canonical order.
 *
 * Endpoints:
 *   GET  /deadlock/reproduce      — fire the textbook A→B / B→A race; expect 40P01.
 *   GET  /deadlock/graph          — snapshot pg_locks + pg_blocking_pids during the race.
 *   GET  /deadlock/lock-ordering  — same workload, canonical id order, no deadlock.
 *   GET  /deadlock/retry          — leave the buggy ordering, wrap with a retry loop.
 *
 * All endpoints live under :8212.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
