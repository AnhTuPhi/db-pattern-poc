package com.claude.dbpoc.m23;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Four Hikari pools, one per "shard". In a real deployment each
 * jdbc-url points at a different host; in the POC they point at
 * different schemas in the same Postgres so you can run the demo
 * with one container.
 *
 * The pools are also exposed as a {@code Map<ShardId, HikariDataSource>}
 * for the router to look up by shard id.
 */
@Configuration
public class DataSourceConfig {

    @Bean @Primary
    @ConfigurationProperties("spring.datasource.s0")
    public HikariDataSource s0() { return DataSourceBuilder.create().type(HikariDataSource.class).build(); }

    @Bean
    @ConfigurationProperties("spring.datasource.s1")
    public HikariDataSource s1() { return DataSourceBuilder.create().type(HikariDataSource.class).build(); }

    @Bean
    @ConfigurationProperties("spring.datasource.s2")
    public HikariDataSource s2() { return DataSourceBuilder.create().type(HikariDataSource.class).build(); }

    @Bean
    @ConfigurationProperties("spring.datasource.s3")
    public HikariDataSource s3() { return DataSourceBuilder.create().type(HikariDataSource.class).build(); }

    /**
     * Ordered map of shard id → datasource. The order matters because
     * the router uses the keys to build the consistent-hash ring on
     * boot.
     */
    @Bean
    public Map<String, HikariDataSource> shards(HikariDataSource s0,
                                                HikariDataSource s1,
                                                HikariDataSource s2,
                                                HikariDataSource s3) {
        Map<String, HikariDataSource> m = new LinkedHashMap<>();
        m.put("s0", s0);
        m.put("s1", s1);
        m.put("s2", s2);
        m.put("s3", s3);
        return m;
    }
}
