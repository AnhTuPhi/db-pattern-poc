package com.claude.dbpoc.m10;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One TransactionTemplate bean wired with the auto-configured
 * PlatformTransactionManager. The services clone it per call when they
 * need a specific isolation level so the same code can run under
 * READ_COMMITTED, REPEATABLE_READ, and SERIALIZABLE from a query
 * parameter.
 */
@Configuration
public class TxConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager ptm) {
        return new TransactionTemplate(ptm);
    }
}
