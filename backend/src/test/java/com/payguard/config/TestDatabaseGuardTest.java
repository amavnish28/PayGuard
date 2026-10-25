package com.payguard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class TestDatabaseGuardTest {

    private static final String DEV_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/payguard";
    private static final String TEST_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/payguard_test";

    @Test
    @DisplayName("Fail-fast guard rejects the development database URL and throws IllegalStateException")
    void testRejectsDevelopmentDatasourceUrl() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> TestDatabaseGuard.validateDatasourceUrl(DEV_DATASOURCE_URL),
                "Guard must throw IllegalStateException when active datasource points to dev database"
        );

        assertTrue(
                ex.getMessage().contains("payguard_test"),
                "Error message must specify required test database 'payguard_test'"
        );
        assertTrue(
                ex.getMessage().contains(DEV_DATASOURCE_URL),
                "Error message must report the rejected dev datasource URL"
        );
        assertTrue(
                ex.getMessage().contains("FAIL-FAST TEST ISOLATION GUARD"),
                "Error message must clearly identify as fail-fast isolation guard"
        );
    }

    @Test
    @DisplayName("Fail-fast guard rejects null, empty, and non-test URLs")
    void testRejectsInvalidUrls() {
        assertThrows(IllegalStateException.class, () -> TestDatabaseGuard.validateDatasourceUrl(null));
        assertThrows(IllegalStateException.class, () -> TestDatabaseGuard.validateDatasourceUrl(""));
        assertThrows(IllegalStateException.class, () -> TestDatabaseGuard.validateDatasourceUrl("jdbc:postgresql://localhost:5432/production"));
        assertThrows(IllegalStateException.class, () -> TestDatabaseGuard.validateDatasourceUrl("jdbc:postgresql://localhost:5432/postgres"));
    }

    @Test
    @DisplayName("Fail-fast guard accepts valid payguard_test URLs without throwing")
    void testAcceptsTestDatasourceUrl() {
        assertDoesNotThrow(() -> TestDatabaseGuard.validateDatasourceUrl(TEST_DATASOURCE_URL));
        assertDoesNotThrow(() -> TestDatabaseGuard.validateDatasourceUrl("jdbc:postgresql://localhost:5432/payguard_test?sslmode=disable"));
    }

    @Test
    @DisplayName("Spring ApplicationContext startup is aborted when configured with the dev database URL")
    void testContextStartupFailsWhenDevUrlConfigured() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(TestDatabaseGuard.class)
                .withPropertyValues("spring.datasource.url=" + DEV_DATASOURCE_URL);

        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("payguard_test")
                    .hasMessageContaining(DEV_DATASOURCE_URL);
        });
    }

    @Test
    @DisplayName("Spring ApplicationContext startup succeeds when configured with the test database URL")
    void testContextStartupSucceedsWhenTestUrlConfigured() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(TestDatabaseGuard.class)
                .withPropertyValues("spring.datasource.url=" + TEST_DATASOURCE_URL);

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TestDatabaseGuard.class);
        });
    }
}
