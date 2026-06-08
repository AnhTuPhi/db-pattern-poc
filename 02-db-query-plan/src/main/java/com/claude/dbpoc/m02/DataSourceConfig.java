package com.claude.dbpoc.m02;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Two Hikari pools, two JdbcTemplates — wired by hand because Boot's
 * auto-config only creates one DataSource and we need both Postgres and
 * (optionally) Oracle live in the same JVM so the user can compare plans
 * side-by-side at runtime.
 *
 * Oracle is gated by @ConditionalOnProperty(name = "oracle.enabled") so a
 * developer with only Postgres up doesn't get a startup failure when the
 * Oracle driver can't reach localhost:1521.
 */
@Configuration
public class DataSourceConfig {

    // ----- Postgres (primary) ----------------------------------------------

    @Bean(destroyMethod = "close")
    @Primary
    public DataSource pgDataSource(
            @Value("${datasources.pg.jdbc-url}") String url,
            @Value("${datasources.pg.username}") String user,
            @Value("${datasources.pg.password}") String pass,
            @Value("${datasources.pg.driver-class-name}") String driver,
            @Value("${datasources.pg.schema}") String schema,
            @Value("${datasources.pg.maximum-pool-size:5}") int maxPool,
            @Value("${datasources.pg.minimum-idle:1}") int minIdle) {

        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("pg-pool");
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setDriverClassName(driver);
        cfg.setMaximumPoolSize(maxPool);
        cfg.setMinimumIdle(minIdle);
        // Setting search_path on every connection means we don't have to
        // schema-qualify every query in this module.
        cfg.setConnectionInitSql("SET search_path TO " + schema);
        return new HikariDataSource(cfg);
    }

    @Bean
    @Primary
    public JdbcTemplate pgJdbc(@Qualifier("pgDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    // ----- Oracle (optional) -----------------------------------------------

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "oracle.enabled", havingValue = "true")
    public DataSource oracleDataSource(
            @Value("${datasources.oracle.jdbc-url}") String url,
            @Value("${datasources.oracle.username}") String user,
            @Value("${datasources.oracle.password}") String pass,
            @Value("${datasources.oracle.driver-class-name}") String driver,
            @Value("${datasources.oracle.maximum-pool-size:5}") int maxPool,
            @Value("${datasources.oracle.minimum-idle:1}") int minIdle) {

        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("oracle-pool");
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setDriverClassName(driver);
        cfg.setMaximumPoolSize(maxPool);
        cfg.setMinimumIdle(minIdle);
        return new HikariDataSource(cfg);
    }

    @Bean
    @ConditionalOnProperty(name = "oracle.enabled", havingValue = "true")
    public JdbcTemplate oracleJdbc(@Qualifier("oracleDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
