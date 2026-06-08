package com.claude.dbpoc.m05;

import com.claude.dbpoc.common.SqlCounter;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps the auto-configured DataSource in SqlCounter so every controller can
 * call {@code sqlCounter.reset()} then read the exact JDBC statement count
 * after the demo work runs.
 *
 * Why a BeanPostProcessor instead of @Primary?
 *   - Spring Boot autoconfigures Hikari + JPA from spring.datasource.* — we
 *     want to keep all that config, just intercept the connections it hands out.
 *   - A BeanPostProcessor lets us wrap the *real* DataSource bean before any
 *     JPA / Hibernate code grabs a reference to it, and avoids the @Primary
 *     duplication-of-config trap.
 *   - p6spy is *also* wrapping the DataSource (added by its starter). Order:
 *         Hikari -> p6spy proxy -> SqlCounter (this wrapper, outermost)
 *     so SqlCounter counts logical executes the same way the human sees them
 *     in p6spy's log output.
 *
 * Holder pattern: the BeanPostProcessor builds the SqlCounter eagerly (it
 * needs to wrap before Hibernate sees the DataSource), then a @Bean method
 * publishes that same instance for @Autowired injection.
 */
@Configuration
public class DataSourceConfig {

    /** Single instance shared by the post-processor and the @Bean method. */
    private static final SqlCounterHolder HOLDER = new SqlCounterHolder();

    /**
     * Runs *during* bean creation of the DataSource, so the wrapped instance
     * is what Hibernate / JpaRepositories receive. Without this, JPA would
     * keep a reference to the raw Hikari pool and our counter would never tick.
     */
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

    /** Publishes the same wrapper instance for controllers to @Autowired. */
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
