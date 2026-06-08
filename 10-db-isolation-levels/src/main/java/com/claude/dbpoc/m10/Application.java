package com.claude.dbpoc.m10;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Module 10 — db-isolation-levels.
 *
 * Run with:    mvn -pl 10-db-isolation-levels spring-boot:run
 * Postgres:    bring up via ../scripts/setup.sh postgres
 *
 * The thesis: "you don't understand isolation levels until you've watched
 * one thread eat another thread's UPDATE in a debugger."
 *
 * Every endpoint runs a deterministic two-thread race that REPRODUCES a
 * specific isolation anomaly — dirty read, non-repeatable read, phantom,
 * lost update — and reports the actual outcome on Postgres (which uses
 * snapshot isolation and DIVERGES from the SQL standard at REPEATABLE_READ
 * and above).
 *
 * The headline demo is /demo/lost-update: two concurrent transfers, $100
 * starting balance, each adds $50, expected $200, actual $150 under
 * READ_COMMITTED. Then the five fixes (optimistic, pessimistic, CAS,
 * SERIALIZABLE, retry) are each demonstrated end-to-end.
 *
 * All endpoints live under :8210.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
