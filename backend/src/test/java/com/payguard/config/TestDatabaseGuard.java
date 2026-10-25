package com.payguard.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Fail-fast test isolation guard.
 * Aborts test startup with a clear error if the active datasource URL does not contain "payguard_test".
 * Ensures tests never connect to the development database ("payguard") or any other database.
 */
@Component
public class TestDatabaseGuard implements
        ApplicationContextInitializer<ConfigurableApplicationContext>,
        BeanFactoryPostProcessor,
        BeanPostProcessor,
        EnvironmentAware,
        PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(TestDatabaseGuard.class);
    public static final String REQUIRED_DB_NAME = "payguard_test";

    private Environment environment;

    public TestDatabaseGuard() {
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        log.info("TestDatabaseGuard: Verifying datasource URL during ApplicationContext initialization...");
        String datasourceUrl = applicationContext.getEnvironment().getProperty("spring.datasource.url");
        validateDatasourceUrl(datasourceUrl);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        log.info("TestDatabaseGuard: Verifying datasource URL during BeanFactory post-processing...");
        if (environment != null) {
            String datasourceUrl = environment.getProperty("spring.datasource.url");
            validateDatasourceUrl(datasourceUrl);
        }
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource) {
            if (bean instanceof HikariDataSource hikariDataSource) {
                log.info("TestDatabaseGuard: Verifying HikariDataSource JDBC URL: {}", hikariDataSource.getJdbcUrl());
                validateDatasourceUrl(hikariDataSource.getJdbcUrl());
            }
        }
        return bean;
    }

    /**
     * Validates that the active datasource URL is non-null and contains "payguard_test".
     *
     * @param datasourceUrl the JDBC URL to validate
     * @throws IllegalStateException if the URL is null or does not contain "payguard_test"
     */
    public static void validateDatasourceUrl(String datasourceUrl) {
        if (datasourceUrl == null || !datasourceUrl.contains(REQUIRED_DB_NAME)) {
            String errorMsg = String.format(
                    "FAIL-FAST TEST ISOLATION GUARD: Active datasource URL '%s' does not contain required test database '%s'. "
                    + "Test execution aborted! Tests must NEVER connect to the development database 'payguard' to avoid data destruction.",
                    datasourceUrl, REQUIRED_DB_NAME
            );
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }
        log.info("TestDatabaseGuard: Datasource URL verified safely targeting test database: {}", datasourceUrl);
    }
}
