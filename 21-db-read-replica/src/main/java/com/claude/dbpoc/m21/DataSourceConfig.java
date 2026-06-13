package com.claude.dbpoc.m21;

import com.claude.dbpoc.m21.routing.RoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Two physical DataSources (primary + replica), one logical RoutingDataSource
 * wrapped in a LazyConnectionDataSourceProxy.
 *
 * The LAZY proxy matters: Spring's tx manager calls getConnection() BEFORE
 * it has applied the readOnly flag to TransactionSynchronizationManager.
 * Without the lazy proxy, every tx would resolve to PRIMARY because
 * isCurrentTransactionReadOnly() returns false at lookup time. The lazy
 * proxy defers the real lookup to the first JDBC operation, by which
 * time the flag is set.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.primary")
    public HikariDataSource primaryDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.replica")
    public HikariDataSource replicaDataSource() {
        return new HikariDataSource();
    }

    @Bean
    public RoutingDataSource routingDataSource(
            HikariDataSource primaryDataSource,
            HikariDataSource replicaDataSource) {
        RoutingDataSource r = new RoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(RoutingDataSource.Target.PRIMARY, primaryDataSource);
        targets.put(RoutingDataSource.Target.REPLICA, replicaDataSource);
        r.setTargetDataSources(targets);
        r.setDefaultTargetDataSource(primaryDataSource);
        return r;
    }

    @Bean
    @Primary
    public DataSource dataSource(RoutingDataSource routingDataSource) {
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }
}
