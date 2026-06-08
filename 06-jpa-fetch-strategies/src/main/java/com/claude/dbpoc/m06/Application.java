package com.claude.dbpoc.m06;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.claude.dbpoc.common.SqlCounter;

/**
 * Module 06 — jpa-fetch-strategies.
 *
 * Run with:    mvn -pl 06-jpa-fetch-strategies spring-boot:run
 * OSIV demo:   mvn -pl 06-jpa-fetch-strategies spring-boot:run -Dspring-boot.run.profiles=osiv-on
 * Postgres:    bring up via ../scripts/setup.sh postgres
 *
 * Thesis (repeated everywhere in this module because it really is the lesson):
 *
 *     "Fetch strategy is a property of the use case, not the entity."
 *
 * EAGER on the entity bakes a join into every query that touches it. That's
 * almost never what the caller actually wants. The right defaults are:
 *
 *     - LAZY on every association (especially ManyToOne, which defaults EAGER)
 *     - JOIN FETCH / @EntityGraph when the use case actually needs the graph
 *     - DTO projection for read paths that go straight to JSON
 *
 * All endpoints live under :8206.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /**
     * Wrap Hikari with SqlCounter so each demo can return a "this took N
     * statements" number — the whole point of the module is to make those
     * numbers visible.
     */
    @Configuration
    static class SqlCounterConfig {

        @Bean
        @Primary
        public SqlCounter sqlCounter(DataSource hikari) {
            return new SqlCounter(hikari);
        }
    }
}
