package com.claude.dbpoc.m13;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Module 13 — db-optimistic-vs-pessimistic.
 *
 * Run with:    mvn -pl 13-db-optimistic-vs-pessimistic spring-boot:run
 * Postgres:    bring up via ../scripts/setup.sh postgres
 *
 * Thesis: "@Version (optimistic) vs SELECT FOR UPDATE (pessimistic) vs
 * UPDATE ... SET col=col+? (CAS) under the same write workload, at low,
 * medium, and high contention. The hot-row crossover is real."
 *
 * Each endpoint runs N threads, each doing K iterations of "read $balance,
 * add $1, write back" under one of the three strategies. Hot-row mode
 * pins every thread on accountId=1 so the conflict rate is maximal;
 * dispersed mode partitions threads across accountCount rows so the
 * conflict rate is near zero. The JSON response reports ops, retries,
 * elapsed ms, and ops/sec — that's the data the production decision
 * actually needs.
 *
 * Expected shape of the result (sane Postgres on localhost, 8 threads):
 *   hot row → pessimistic > cas > optimistic   (optimistic burns CPU on retries)
 *   dispersed → cas > optimistic ≈ pessimistic (no contention, FOR UPDATE is wasted ceremony)
 *
 * All endpoints live under :8213.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
