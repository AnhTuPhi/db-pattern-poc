package com.claude.dbpoc.m21.routing;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Decides which underlying DataSource each connection request goes to.
 * The decision is made at LOOKUP time (when the tx manager opens the
 * connection), based on whether the current transaction is read-only.
 *
 * The mechanism:
 *   - Spring's tx manager sets `currentTransactionReadOnly()` BEFORE
 *     it calls getConnection().
 *   - We read that flag here and return either "PRIMARY" or "REPLICA".
 *   - AbstractRoutingDataSource looks up the key in the map of
 *     real DataSources.
 *
 * The CRITICAL rule: Connections are bound to a tx for its lifetime.
 * You CANNOT switch DataSources mid-transaction. That means:
 *   - The @Transactional annotation MUST be at the call boundary that
 *     opens the tx. Nested @Transactional calls inherit the outer flag.
 *   - A @Transactional(readOnly=true) method that ends up writing will
 *     either error or commit to the replica (which is read-only). Both
 *     are bad. Pick one mode per method.
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    public enum Target { PRIMARY, REPLICA }

    /**
     * Used by the sticky-window fix — if a thread-local key is set
     * (e.g. just after a write), force PRIMARY for subsequent reads even
     * when readOnly=true.
     */
    public static final ThreadLocal<Boolean> FORCE_PRIMARY = new ThreadLocal<>();

    @Override
    protected Object determineCurrentLookupKey() {
        Boolean force = FORCE_PRIMARY.get();
        if (Boolean.TRUE.equals(force)) return Target.PRIMARY;
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            ? Target.REPLICA
            : Target.PRIMARY;
    }
}
