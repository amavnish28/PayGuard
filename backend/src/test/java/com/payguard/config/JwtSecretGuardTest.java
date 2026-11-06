package com.payguard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class JwtSecretGuardTest {

    private static final String VALID_SECRET = "abcdefghijklmnopqrstuvwxyz0123456789"; // 36 chars
    private static final String SHORT_SECRET = "short-secret-only-19-ch"; // 23 chars

    @Test
    @DisplayName("validateSecret throws IllegalStateException naming PAYGUARD_JWT_SECRET when secret is null")
    void testValidateSecretThrowsWhenNull() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> JwtSecretGuard.validateSecret(null)
        );
        assertTrue(ex.getMessage().contains("PAYGUARD_JWT_SECRET"),
                "Error message must name the missing PAYGUARD_JWT_SECRET variable");
    }

    @Test
    @DisplayName("validateSecret throws IllegalStateException naming PAYGUARD_JWT_SECRET when secret is empty or blank")
    void testValidateSecretThrowsWhenEmptyOrBlank() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> JwtSecretGuard.validateSecret("   ")
        );
        assertTrue(ex.getMessage().contains("PAYGUARD_JWT_SECRET"),
                "Error message must name the missing PAYGUARD_JWT_SECRET variable");
    }

    @Test
    @DisplayName("validateSecret throws IllegalStateException naming PAYGUARD_JWT_SECRET when secret is shorter than 32 characters")
    void testValidateSecretThrowsWhenTooShort() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> JwtSecretGuard.validateSecret(SHORT_SECRET)
        );
        assertTrue(ex.getMessage().contains("PAYGUARD_JWT_SECRET"),
                "Error message must name the missing PAYGUARD_JWT_SECRET variable");
        assertTrue(ex.getMessage().contains("32"),
                "Error message must indicate the 32-character minimum requirement");
    }

    @Test
    @DisplayName("validateSecret passes without throwing when secret is at least 32 characters")
    void testValidateSecretPassesWhenValid() {
        assertDoesNotThrow(() -> JwtSecretGuard.validateSecret(VALID_SECRET));
    }

    @Test
    @DisplayName("Startup aborts outside test profile when PAYGUARD_JWT_SECRET is missing")
    void testStartupAbortsOutsideTestProfileWhenSecretMissing() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withSystemProperties("spring.profiles.active=prod")
                .withUserConfiguration(JwtSecretGuard.class);

        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PAYGUARD_JWT_SECRET");
        });
    }

    @Test
    @DisplayName("Startup aborts outside test profile when PAYGUARD_JWT_SECRET is shorter than 32 characters")
    void testStartupAbortsOutsideTestProfileWhenSecretTooShort() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withSystemProperties("spring.profiles.active=prod")
                .withUserConfiguration(JwtSecretGuard.class)
                .withPropertyValues("PAYGUARD_JWT_SECRET=" + SHORT_SECRET);

        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PAYGUARD_JWT_SECRET")
                    .hasMessageContaining("32");
        });
    }

    @Test
    @DisplayName("Startup succeeds inside test profile even without PAYGUARD_JWT_SECRET")
    void testStartupSucceedsInsideTestProfile() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(JwtSecretGuard.class)
                .withPropertyValues("spring.profiles.active=test");

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JwtSecretGuard.class);
        });
    }

    @Test
    @DisplayName("Startup succeeds outside test profile when PAYGUARD_JWT_SECRET is at least 32 characters")
    void testStartupSucceedsOutsideTestProfileWithValidSecret() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(JwtSecretGuard.class)
                .withPropertyValues("PAYGUARD_JWT_SECRET=" + VALID_SECRET);

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JwtSecretGuard.class);
        });
    }
}
