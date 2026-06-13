package com.claude.dbpoc.m11;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Module 11 — db-locking.
 *
 * Run with:    mvn -pl 11-db-locking spring-boot:run
 * Postgres:    bring up via ../scripts/setup.sh postgres
 *
 * The thesis: "the same SELECT FOR UPDATE you've been writing has three
 * production-grade variants — plain (waits), SKIP LOCKED (queue
 * workers), NOWAIT (fail-fast) — plus table-level locks for DDL-style
 * coordination, and the pg_locks view to see them all in flight."
 *
 * The headline endpoint is /locks/skip-locked — the canonical SQL-only
 * job queue. Spin up N workers, each runs:
 *   SELECT * FROM job WHERE status='PENDING' ORDER BY id LIMIT M
 *   FOR UPDATE SKIP LOCKED
 * Two workers will never see the same row, nobody waits, the DB is the
 * coordinator. This is one of the highest-leverage primitives in
 * Postgres and the reason this module exists.
 *
 * All endpoints live under :8211.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
