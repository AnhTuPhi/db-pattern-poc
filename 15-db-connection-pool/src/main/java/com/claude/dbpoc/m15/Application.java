package com.claude.dbpoc.m15;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Module 15 — db-connection-pool.
 *
 * Run with:    mvn -pl 15-db-connection-pool spring-boot:run
 * Postgres:    bring up via ../scripts/setup.sh postgres
 *
 * Thesis: pool sizing is math, not vibes. The famous Brett Wooldridge
 * starting point is
 *
 *     connections = ((core_count * 2) + effective_spindle_count)
 *
 * That's a STARTING POINT, not the answer. The real answer is "measure
 * under your workload, then pick the knee of the latency curve." This
 * module gives you the knobs (maximum-pool-size, minimum-idle,
 * connection-timeout, idle-timeout, max-lifetime, validation-timeout)
 * and the live HikariPoolMXBean so you can SEE what each one does.
 *
 * All endpoints live under :8215.
 *
 * Endpoints:
 *   GET /pool/inspect         — live pool stats + the configured knobs.
 *   GET /pool/stress          — N parallel acquires, hold for holdMs.
 *   GET /pool/sizing-math     — static Wooldridge formula + opinion.
 *   GET /pool/lifetime-rotation — read pool churn over time.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
