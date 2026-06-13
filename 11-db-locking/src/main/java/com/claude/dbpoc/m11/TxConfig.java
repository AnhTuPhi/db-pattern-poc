package com.claude.dbpoc.m11;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single TransactionTemplate bean wired with the auto-configured
 * PlatformTransactionManager. The locking demos use it programmatically
 * because every demo needs to control WHEN a transaction commits — the
 * lock is only released at that exact moment, and the race
 * choreography depends on observing that release.
 *
 * Using @Transactional methods would also work, but the explicit
 * tx.execute(...) lambdas make the "tx starts here, lock taken here,
 * commit here" structure visible in the source — which is the whole
 * point of a teaching POC about locks.
 */
@Configuration
public class TxConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager ptm) {
        return new TransactionTemplate(ptm);
    }
}
