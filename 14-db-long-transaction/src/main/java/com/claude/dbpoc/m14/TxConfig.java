package com.claude.dbpoc.m14;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expose a TransactionTemplate so the service layer can open transactions
 * programmatically from background threads (where @Transactional wouldn't
 * apply). All the "long tx" demos need explicit tx control — that's the
 * whole point.
 */
@Configuration
public class TxConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager ptm) {
        return new TransactionTemplate(ptm);
    }
}
