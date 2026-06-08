package com.claude.dbpoc.common;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dynamic-proxy plumbing for SqlCounter. Counts every executeXxx / executeBatch
 * call on Statement and PreparedStatement instances handed back by the wrapped
 * Connection. Kept package-private; only SqlCounter constructs instances.
 */
final class CountingConnection {

    static Connection wrap(Connection delegate, AtomicLong stmtCount, AtomicLong batchCount) {
        return (Connection) Proxy.newProxyInstance(
            CountingConnection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            new ConnectionHandler(delegate, stmtCount, batchCount));
    }

    private record ConnectionHandler(Connection delegate, AtomicLong stmts, AtomicLong batches)
            implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = method.invoke(delegate, args);
            if (result instanceof PreparedStatement ps) {
                return wrapStatement(ps, PreparedStatement.class, stmts, batches);
            }
            if (result instanceof Statement st) {
                return wrapStatement(st, Statement.class, stmts, batches);
            }
            return result;
        }
    }

    private static <T extends Statement> T wrapStatement(T target, Class<T> iface,
                                                        AtomicLong stmts, AtomicLong batches) {
        @SuppressWarnings("unchecked")
        T proxy = (T) Proxy.newProxyInstance(
            CountingConnection.class.getClassLoader(),
            new Class<?>[]{iface},
            (p, method, args) -> {
                String name = method.getName();
                if (name.startsWith("execute")) {
                    if (name.equals("executeBatch") || name.equals("executeLargeBatch")) {
                        batches.incrementAndGet();
                    } else {
                        stmts.incrementAndGet();
                    }
                }
                return method.invoke(target, args);
            });
        return proxy;
    }

    private CountingConnection() {}
}
