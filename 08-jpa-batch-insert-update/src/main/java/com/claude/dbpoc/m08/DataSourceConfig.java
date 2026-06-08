package com.claude.dbpoc.m08;

import com.claude.dbpoc.common.SqlCounter;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps the auto-configured DataSource in SqlCounter so every bench can call
 * {@code sqlCounter.reset()} then read the exact statement + batch counts
 * after the work runs — that's the number we publish in the JSON response.
 *
 * Same pattern as module 05. See its DataSourceConfig for the rationale on
 * BeanPostProcessor vs @Primary. Order of wrappers ends up:
 *     Hikari -> p6spy -> SqlCounter (outermost)
 * so SqlCounter counts the same executes the human sees in the p6spy log.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public SqlCounter sqlCounter() {
        return new SqlCounter(null); // delegate wired by the post-processor below
    }

    @Bean
    public static BeanPostProcessor dataSourceWrapper(SqlCounter sqlCounter) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof SqlCounter)) {
                    sqlCounter.setTargetDataSource(ds);
                    return sqlCounter;
                }
                return bean;
            }
        };
    }
}
