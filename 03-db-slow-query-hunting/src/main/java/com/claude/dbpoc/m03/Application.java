package com.claude.dbpoc.m03;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Module 03 — Slow Query Hunting.
 *
 * One Spring Boot process exposes:
 *   POST /seed                          -> populate the bait tables
 *   POST /workload/start?seconds=N      -> run mixed good+bad SQL for N seconds
 *   GET  /top?n=10&order=total_time     -> top-N from pg_stat_statements
 *   POST /reset                         -> pg_stat_statements_reset()
 *   GET  /suggest/missing-fk-indexes    -> Postgres unindexed-FK report
 *   GET  /top/oracle?n=10               -> V$SQL top-N    (oracle.enabled=true)
 *   GET  /suggest/oracle/missing-fk-... -> Oracle equivalent
 *
 * The Postgres datasource is always present. The Oracle datasource is
 * conditionally created only if oracle.enabled=true so dev environments
 * without an Oracle container still boot cleanly.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    // ---------------------------------------------------------------------
    // Postgres = primary. Spring Boot auto-configures the default one from
    // spring.datasource.*, so no bean override is needed here — we just
    // expose a named JdbcTemplate for clarity in the controllers.
    // ---------------------------------------------------------------------
    @Configuration
    static class PostgresConfig {
        @Bean
        JdbcTemplate pgJdbc(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    // ---------------------------------------------------------------------
    // Oracle = optional. Only wired up when oracle.enabled=true so that
    // boot in pg-only dev mode doesn't fail trying to connect to 1521.
    // ---------------------------------------------------------------------
    @Configuration
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "oracle.enabled", havingValue = "true")
    static class OracleConfig {

        @Bean(name = "oracleDataSource")
        DataSource oracleDataSource(
                @Value("${oracle.url}") String url,
                @Value("${oracle.username}") String user,
                @Value("${oracle.password}") String pass) {
            return DataSourceBuilder.create()
                    .driverClassName("oracle.jdbc.OracleDriver")
                    .url(url)
                    .username(user)
                    .password(pass)
                    .build();
        }

        @Bean(name = "oracleJdbc")
        JdbcTemplate oracleJdbc(DataSource oracleDataSource) {
            return new JdbcTemplate(oracleDataSource);
        }
    }
}
