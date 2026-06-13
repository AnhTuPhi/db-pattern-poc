package com.claude.dbpoc.m13;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TransactionTemplate wired to the auto-configured PlatformTransactionManager.
 *
 * Why a TransactionTemplate and not @Transactional methods on the service:
 *   - Each benchmark iteration must run in its OWN transaction so the work
 *     unit, the retry semantics, and the wall-clock timing are honest.
 *     @Transactional with REQUIRES_NEW works, but adds proxy/JoinPoint
 *     overhead per iteration that can distort microbenchmarks.
 *   - The template lets the worker loop start a fresh tx per +$1 increment,
 *     catch ObjectOptimisticLockingFailureException directly without losing
 *     the loop's stack, and decide whether to retry without ceding control
 *     to a Spring proxy.
 *
 * Read READ_COMMITTED by default — that's the Postgres default and the
 * only level at which the three strategies have meaningfully different
 * costs (SERIALIZABLE would homogenise the abort rates).
 */
@Configuration
public class TxConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager ptm) {
        return new TransactionTemplate(ptm);
    }
}
