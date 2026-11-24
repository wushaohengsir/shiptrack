package com.shiptrack.api;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Fail-fast guard for the one-shot {@code migrate} profile.
 *
 * <p>Spring Boot's binder leaves unresolved placeholders untouched instead of failing,
 * so a missing {@code DB_MIGRATION_USER}/{@code DB_MIGRATION_PASSWORD} would otherwise
 * surface later as an obscure connection error. This guard aborts startup before any
 * bean (in particular Flyway) is created, and never falls back to the API runtime
 * account ({@code DB_USER}/{@code DB_PASSWORD}) — 技术栈使用说明 §3.7.
 */
@Configuration(proxyBeanMethods = false)
@Profile("migrate")
class MigrateCredentialGuardConfiguration {

    private static final String USERNAME_PROPERTY = "spring.datasource.username";
    private static final String PASSWORD_PROPERTY = "spring.datasource.password";

    @Bean
    static BeanFactoryPostProcessor requireMigrationCredentials(Environment environment) {
        return beanFactory -> {
            requireResolvable(environment, USERNAME_PROPERTY, "DB_MIGRATION_USER");
            requireResolvable(environment, PASSWORD_PROPERTY, "DB_MIGRATION_PASSWORD");
        };
    }

    private static void requireResolvable(Environment environment, String property, String variable) {
        String value;
        try {
            value = environment.getProperty(property);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            throw new IllegalStateException(
                    "migrate profile requires " + variable
                            + "; falling back to DB_USER/DB_PASSWORD is not allowed",
                    unresolvedPlaceholder);
        }
        if (value == null || value.isBlank() || value.startsWith("${")) {
            throw new IllegalStateException(
                    "migrate profile requires " + variable
                            + "; falling back to DB_USER/DB_PASSWORD is not allowed");
        }
    }
}
