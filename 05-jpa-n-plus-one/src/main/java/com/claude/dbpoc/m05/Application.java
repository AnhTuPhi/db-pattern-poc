package com.claude.dbpoc.m05;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Module 05 — The N+1 problem and five side-by-side fixes.
 *
 * Boot a tiny REST service on :8205. The headline endpoint is
 * GET /compare/all?orders=100 — it runs every variant in sequence and returns
 * a JSON table with the actual SQL count of each one.
 *
 * @EnableCaching wires up Spring's CacheManager. Hibernate's L2 cache uses
 * JCache directly, but Spring picking up Ehcache as a provider too keeps the
 * two layers (Spring cache + Hibernate L2) cooperating cleanly.
 */
@SpringBootApplication
@EnableCaching
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
