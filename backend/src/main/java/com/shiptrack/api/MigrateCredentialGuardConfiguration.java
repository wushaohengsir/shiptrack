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
 *
 * <p>It checks the migration variables themselves first, then the final
 * {@code spring.datasource.*} values. Checking only the latter is not enough: a
 * higher-precedence source ({@code SPRING_DATASOURCE_*}, {@code -Dspring.datasource.*}
 * or a command-line argument) can override the YAML placeholders and would otherwise
 * let a different account reach Flyway without any migration credential being set.
 */
@Configuration(proxyBeanMethods = false)
@Profile("migrate")
class MigrateCredentialGuardConfiguration {

    private static final String USERNAME_PROPERTY = "spring.datasource.username";
    private static final String PASSWORD_PROPERTY = "spring.datasource.password";
    private static final String USERNAME_VARIABLE = "DB_MIGRATION_USER";
    private static final String PASSWORD_VARIABLE = "DB_MIGRATION_PASSWORD";

    @Bean
    static BeanFactoryPostProcessor requireMigrationCredentials(Environment environment) {
        return beanFactory -> {
            String username = requireVariable(environment, USERNAME_VARIABLE);
            String password = requireVariable(environment, PASSWORD_VARIABLE);
            requireMatchesVariable(environment, USERNAME_PROPERTY, USERNAME_VARIABLE, username);
            requireMatchesVariable(environment, PASSWORD_PROPERTY, PASSWORD_VARIABLE, password);
        };
    }

    /** The migration variable must be set and non-blank, wherever it resolves from. */
    private static String requireVariable(Environment environment, String variable) {
        String value;
        try {
            value = environment.getProperty(variable);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            throw missingVariable(variable, unresolvedPlaceholder);
        }
        if (value == null || value.isBlank() || value.startsWith("${")) {
            throw missingVariable(variable, null);
        }
        return value;
    }

    /**
     * The datasource credential actually used by Flyway must be exactly the migration
     * variable, so no higher-precedence property can swap in another account.
     */
    private static void requireMatchesVariable(
            Environment environment, String property, String variable, String expected) {
        String actual;
        try {
            actual = environment.getProperty(property);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            throw overridingProperty(property, variable, unresolvedPlaceholder);
        }
        if (!expected.equals(actual)) {
            throw overridingProperty(property, variable, null);
        }
    }

    private static IllegalStateException missingVariable(String variable, Throwable cause) {
        String message = "migrate profile requires " + variable
                + "; falling back to DB_USER/DB_PASSWORD is not allowed";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private static IllegalStateException overridingProperty(
            String property, String variable, Throwable cause) {
        String message = "migrate profile requires " + property + " to come from " + variable
                + "; overriding it with another value is not allowed";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
