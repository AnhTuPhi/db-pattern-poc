package com.claude.dbpoc.m07;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Module 07 — jpa-flush-and-cascade.
 *
 * Run with:    mvn -pl 07-jpa-flush-and-cascade spring-boot:run
 * Postgres:    bring up via ../scripts/setup.sh postgres
 *
 * The thesis: "cascade = ALL is a future foot-gun. Be explicit."
 *
 * Every endpoint demonstrates one specific way Hibernate's persistence
 * context will silently do something you did not ask it to do — cascade a
 * REMOVE you only intended on the parent, orphan-delete every child because
 * you replaced a collection reference, flush pending writes before an
 * unrelated query, or burn CPU dirty-checking 10k entities for one update.
 *
 * All endpoints live under :8207.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
