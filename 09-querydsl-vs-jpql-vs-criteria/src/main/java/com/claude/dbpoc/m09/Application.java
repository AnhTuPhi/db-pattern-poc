package com.claude.dbpoc.m09;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Module 09 — JPQL vs Criteria vs QueryDSL vs native SQL, the same dynamic
 * search query four ways. The goal is a defensible house-rule for which
 * builder to reach for, by call shape.
 *
 * Headline endpoint: GET /compare?customerName=ab&country=US — fans out to
 * all four implementations against the same input, returns each one's
 * elapsedMs + the SQL Hibernate emitted, plus the verdict matrix.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /**
     * QueryDSL's entry point. Auto-configured nowhere — has to be a manual
     * @Bean because the artifact ships without Spring Boot integration. The
     * EntityManager is request-scoped under the covers via Spring's proxy,
     * so a singleton JPAQueryFactory pointing at it is safe.
     */
    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager em) {
        return new JPAQueryFactory(em);
    }
}
