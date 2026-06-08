package com.claude.dbpoc.common;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Wraps a DataSource and counts statements executed since reset(). Every
 * JPA-pitfall POC uses this to *prove* the SQL count of each approach
 * (the N+1 POC, fetch strategies, batch insert, etc).
 *
 * Designed to be simple and thread-local-free — the POC calls happen on the
 * request thread, single connection, so the wrapping is enough.
 */
public class SqlCounter extends DelegatingDataSource {

    private final AtomicLong statementCount = new AtomicLong();
    private final AtomicLong batchCount = new AtomicLong();

    public SqlCounter(DataSource target) {
        super(target);
    }

    public void reset() {
        statementCount.set(0);
        batchCount.set(0);
    }

    public long getStatementCount() { return statementCount.get(); }
    public long getBatchCount() { return batchCount.get(); }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection delegate) {
        return CountingConnection.wrap(delegate, statementCount, batchCount);
    }
}
