package com.claude.dbpoc.m04;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Module 04 — db-cardinality-estimation.
 *
 * Run with:    mvn -pl 04-db-cardinality-estimation spring-boot:run
 * Postgres:    bring up via ../scripts/setup.sh postgres
 *
 * The thesis: "Bad plans are usually bad estimates." Every endpoint surfaces
 * the planner's estimated row count vs the actual row count, so you can SEE
 * the divergence that causes seemingly random performance cliffs in prod.
 *
 * All endpoints live under :8204.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
