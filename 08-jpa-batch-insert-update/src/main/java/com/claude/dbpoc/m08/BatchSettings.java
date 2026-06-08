package com.claude.dbpoc.m08;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Reflects the active Hibernate batching settings back so the JSON bench
 * response includes them as "context". Lets the reader see WHY a number is
 * what it is without having to crack open application.yml.
 *
 * If the setting is missing in the env, Hibernate's defaults are reported
 * (batch_size defaults to 0 — i.e. batching off).
 */
@Component
@Getter
public class BatchSettings {

    private final int batchSize;
    private final boolean orderInserts;
    private final boolean orderUpdates;
    private final boolean batchVersionedData;
    private final boolean pgReWriteBatchedInserts;
    private final String activeProfile;
    private final String jdbcUrl;

    public BatchSettings(
        @Value("${spring.jpa.properties.hibernate.jdbc.batch_size:0}") int batchSize,
        @Value("${spring.jpa.properties.hibernate.order_inserts:false}") boolean orderInserts,
        @Value("${spring.jpa.properties.hibernate.order_updates:false}") boolean orderUpdates,
        @Value("${spring.jpa.properties.hibernate.jdbc.batch_versioned_data:false}") boolean batchVersionedData,
        @Value("${spring.datasource.url:}") String jdbcUrl,
        Environment env
    ) {
        this.batchSize = batchSize;
        this.orderInserts = orderInserts;
        this.orderUpdates = orderUpdates;
        this.batchVersionedData = batchVersionedData;
        this.jdbcUrl = jdbcUrl;
        this.pgReWriteBatchedInserts = jdbcUrl != null && jdbcUrl.contains("reWriteBatchedInserts=true");
        String[] profiles = env.getActiveProfiles();
        this.activeProfile = profiles.length == 0 ? "default" : String.join(",", profiles);
    }
}
