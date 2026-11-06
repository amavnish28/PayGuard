package com.payguard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Fail-fast security guard for JWT secret validation.
 * Outside the "test" profile, application startup must abort with a clear message naming the missing
 * variable if PAYGUARD_JWT_SECRET is absent or shorter than 32 characters.
 */
@Component
public class JwtSecretGuard implements
        ApplicationContextInitializer<ConfigurableApplicationContext>,
        BeanFactoryPostProcessor,
        EnvironmentAware,
        PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(JwtSecretGuard.class);
    public static final String ENV_VAR_NAME = "PAYGUARD_JWT_SECRET";
    public static final String PROPERTY_NAME = "payguard.jwt.secret";
    public static final int MIN_SECRET_LENGTH = 32;

    private Environment environment;

    public JwtSecretGuard() {
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
        log.info("JwtSecretGuard: Verifying PAYGUARD_JWT_SECRET during ApplicationContext initialization...");
        validateEnvironment(applicationContext.getEnvironment());
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        log.info("JwtSecretGuard: Verifying PAYGUARD_JWT_SECRET during BeanFactory post-processing...");
        if (environment != null) {
            validateEnvironment(environment);
        }
    }

    public static void validateEnvironment(Environment env) {
        if (env == null) {
            return;
        }

        // Outside the "test" profile, startup must abort if PAYGUARD_JWT_SECRET is absent or < 32 characters
        if (Arrays.asList(env.getActiveProfiles()).contains("test")) {
            log.info("JwtSecretGuard: Active profile is 'test'; skipping production PAYGUARD_JWT_SECRET validation.");
            return;
        }

        String secret = env.getProperty(ENV_VAR_NAME);
        if (secret == null || secret.isBlank()) {
            try {
                secret = env.getProperty(PROPERTY_NAME);
            } catch (Exception ignored) {
                secret = null;
            }
        }

        validateSecret(secret);
    }

    public static void validateSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            String errorMsg = String.format(
                    "FAIL-FAST SECURITY GUARD: Missing required environment variable '%s'. "
                    + "Application startup aborted! Please set %s to a secret of at least %d characters.",
                    ENV_VAR_NAME, ENV_VAR_NAME, MIN_SECRET_LENGTH
            );
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (secret.trim().length() < MIN_SECRET_LENGTH) {
            String errorMsg = String.format(
                    "FAIL-FAST SECURITY GUARD: Environment variable '%s' is too short (%d characters). "
                    + "Application startup aborted! %s must be at least %d characters long.",
                    ENV_VAR_NAME, secret.trim().length(), ENV_VAR_NAME, MIN_SECRET_LENGTH
            );
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        log.info("JwtSecretGuard: '{}' verified successfully (length: {}).", ENV_VAR_NAME, secret.trim().length());
    }
}
