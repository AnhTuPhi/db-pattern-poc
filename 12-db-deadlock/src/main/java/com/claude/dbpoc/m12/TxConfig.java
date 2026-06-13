package com.claude.dbpoc.m12;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TransactionTemplate so each demo can manage its own transaction
 * boundaries — the deadlock race is timing-sensitive, and we need to
 * know exactly when T1 commits.
 */
@Configuration
public class TxConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager ptm) {
        return new TransactionTemplate(ptm);
    }
}
