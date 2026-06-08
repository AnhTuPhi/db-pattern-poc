package com.claude.dbpoc.m02;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Module 02 — db-query-plan.
 *
 * Run with:    mvn -pl 02-db-query-plan spring-boot:run
 * Postgres:    bring up via ../scripts/setup.sh postgres
 * Oracle:      ../scripts/setup.sh core   AND   --oracle.enabled=true
 *
 * Endpoints live under :8202 — see PlanController / PlanCompareController.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
