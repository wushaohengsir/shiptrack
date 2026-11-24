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
 *
 * <p>Flyway can also build its own migration DataSource from {@code spring.flyway.url},
 * {@code spring.flyway.user} and {@code spring.flyway.password}; Hikari's own
 * {@code spring.datasource.hikari.*} overrides are applied after the standard
 * properties. Both paths are checked as well, so no property can swap the account or
 * target that actually runs the migration.
 */
@Configuration(proxyBeanMethods = false)
@Profile("migrate")
class MigrateCredentialGuardConfiguration {

    private static final String URL_PROPERTY = "spring.datasource.url";
    private static final String USERNAME_PROPERTY = "spring.datasource.username";
    private static final String PASSWORD_PROPERTY = "spring.datasource.password";
    private static final String HIKARI_URL_PROPERTY = "spring.datasource.hikari.jdbc-url";
    private static final String HIKARI_USERNAME_PROPERTY = "spring.datasource.hikari.username";
    private static final String HIKARI_PASSWORD_PROPERTY = "spring.datasource.hikari.password";
    private static final String FLYWAY_URL_PROPERTY = "spring.flyway.url";
    private static final String FLYWAY_USERNAME_PROPERTY = "spring.flyway.user";
    private static final String FLYWAY_PASSWORD_PROPERTY = "spring.flyway.password";
    private static final String USERNAME_VARIABLE = "DB_MIGRATION_USER";
    private static final String PASSWORD_VARIABLE = "DB_MIGRATION_PASSWORD";

    @Bean
    static BeanFactoryPostProcessor requireMigrationCredentials(Environment environment) {
        return beanFactory -> {
            String username = requireVariable(environment, USERNAME_VARIABLE);
            String password = requireVariable(environment, PASSWORD_VARIABLE);

            requireMatchesVariable(environment, USERNAME_PROPERTY, USERNAME_VARIABLE, username);
            requireMatchesVariable(environment, PASSWORD_PROPERTY, PASSWORD_VARIABLE, password);

            // Hikari binds spring.datasource.hikari.* after the standard properties,
            // so these can override the account that actually connects.
            requireAbsentOrMatchesVariable(environment, HIKARI_USERNAME_PROPERTY, USERNAME_VARIABLE, username);
            requireAbsentOrMatchesVariable(environment, HIKARI_PASSWORD_PROPERTY, PASSWORD_VARIABLE, password);

            // Flyway may derive its own DataSource from spring.flyway.*; it must never
            // point somewhere else or use another account.
            String datasourceUrl = requireUrl(environment);
            requireAbsentOrMatchesValue(environment, HIKARI_URL_PROPERTY, datasourceUrl);
            requireAbsentOrMatchesValue(environment, FLYWAY_URL_PROPERTY, datasourceUrl);
            requireAbsentOrMatchesVariable(environment, FLYWAY_USERNAME_PROPERTY, USERNAME_VARIABLE, username);
            requireAbsentOrMatchesVariable(environment, FLYWAY_PASSWORD_PROPERTY, PASSWORD_VARIABLE, password);
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

    /** An optional override is allowed only when it resolves to the guarded value. */
    private static void requireAbsentOrMatchesVariable(
            Environment environment, String property, String variable, String expected) {
        String actual;
        try {
            actual = environment.getProperty(property);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            throw overridingProperty(property, variable, unresolvedPlaceholder);
        }
        if (actual != null && !actual.isBlank()
                && (actual.startsWith("${") || !expected.equals(actual))) {
            throw overridingProperty(property, variable, null);
        }
    }

    private static String requireUrl(Environment environment) {
        String url = environment.getProperty(URL_PROPERTY);
        if (!hasText(url)) {
            throw new IllegalStateException(
                    "migrate profile requires " + URL_PROPERTY + "; the migration target is not configured");
        }
        return url;
    }

    private static void requireAbsentOrMatchesValue(
            Environment environment, String property, String expected) {
        String actual;
        try {
            actual = environment.getProperty(property);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            throw urlMismatch(property, unresolvedPlaceholder);
        }
        if (actual != null && !actual.isBlank()
                && (actual.startsWith("${") || !expected.equals(actual))) {
            throw urlMismatch(property, null);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank() && !value.startsWith("${");
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

    private static IllegalStateException urlMismatch(String property, Throwable cause) {
        String message = "migrate profile requires " + property + " to match " + URL_PROPERTY
                + "; pointing the migration at another database is not allowed";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
