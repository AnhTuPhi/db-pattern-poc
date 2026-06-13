package com.claude.dbpoc.m17;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Two pools:
 *
 *   - mainPool (10 connections) — handles all the "fast" traffic.
 *   - slowPool  (4 connections) — bulkhead reserved for the slow code path.
 *
 * The slow path is allowed to saturate slowPool. Main traffic CAN'T use
 * slowPool's slots, so it stays healthy even when the slow path is
 * burning. This is the database equivalent of Hystrix/Resilience4j
 * bulkhead — the resource pool itself is the isolation boundary.
 */
@Configuration
public class PoolConfig {

    @Primary
    @Bean(name = "mainDataSource")
    @ConfigurationProperties("spring.datasource.main")
    public HikariConfig mainHikariConfig() {
        return new HikariConfig();
    }

    @Primary
    @Bean(name = "mainDs")
    public HikariDataSource mainDs(@Qualifier("mainDataSource") HikariConfig cfg) {
        return new HikariDataSource(cfg);
    }

    @Bean(name = "slowDataSource")
    @ConfigurationProperties("spring.datasource.slow")
    public HikariConfig slowHikariConfig() {
        return new HikariConfig();
    }

    @Bean(name = "slowDs")
    public HikariDataSource slowDs(@Qualifier("slowDataSource") HikariConfig cfg) {
        return new HikariDataSource(cfg);
    }

    @Primary
    @Bean(name = "mainJdbc")
    public JdbcTemplate mainJdbc(@Qualifier("mainDs") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean(name = "slowJdbc")
    public JdbcTemplate slowJdbc(@Qualifier("slowDs") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
