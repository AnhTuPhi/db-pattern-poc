package com.claude.dbpoc.m07.config;

import com.claude.dbpoc.common.SqlCounter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wraps the auto-configured DataSource in SqlCounter so every cascade /
 * flush / dirty-check endpoint can call {@code sqlCounter.reset()} then
 * report the exact JDBC statement count after the demo body runs.
 *
 * Layering (outermost → innermost):
 *   SqlCounter  →  p6spy proxy  →  Hikari pool  →  Postgres JDBC
 *
 * SqlCounter is outermost so the number we report matches what p6spy
 * prints (and what a human reading the log would count).
 *
 * Uses a BeanPostProcessor — not @Primary — to keep Spring Boot's
 * autoconfigured Hikari / JPA wiring intact. The wrapper substitutes the
 * DataSource before Hibernate ever holds a reference.
 */
@Configuration
public class SqlCounterConfig {

    private static final SqlCounterHolder HOLDER = new SqlCounterHolder();

    @Bean
    public static BeanPostProcessor dataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof SqlCounter)) {
                    SqlCounter wrapped = new SqlCounter(ds);
                    HOLDER.set(wrapped);
                    return wrapped;
                }
                return bean;
            }
        };
    }

    @Bean
    public SqlCounter sqlCounter() {
        SqlCounter c = HOLDER.get();
        if (c == null) {
            throw new IllegalStateException(
                "DataSource was never post-processed — wrapper did not run");
        }
        return c;
    }

    private static final class SqlCounterHolder {
        private volatile SqlCounter counter;
        void set(SqlCounter c) { this.counter = c; }
        SqlCounter get() { return counter; }
    }
}
