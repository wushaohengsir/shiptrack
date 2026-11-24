package com.shiptrack.api;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.function.Predicate;

/**
 * Fail-fast guard for the one-shot {@code migrate} profile.
 *
 * <p>Spring Boot's binder leaves unresolved placeholders untouched instead of failing,
 * so a missing {@code DB_MIGRATION_USER}/{@code DB_MIGRATION_PASSWORD} would otherwise
 * surface later as an obscure connection error. This guard aborts startup before any
 * bean (in particular Flyway) is created, and never falls back to the API runtime
 * account ({@code DB_USER}/{@code DB_PASSWORD}) — 技术栈使用说明 §3.7.
 *
 * <p>Rules enforced in order:
 * <ol>
 *   <li>{@code DB_MIGRATION_USER} and {@code DB_MIGRATION_PASSWORD} must be set,
 *       non-blank and fully resolved.</li>
 *   <li>{@code spring.datasource.username/password} and the Hikari equivalents must be
 *       exactly those variables, so no higher-precedence source
 *       ({@code SPRING_DATASOURCE_*}, {@code -Dspring.datasource.*} or a command-line
 *       argument) can swap in another account.</li>
 *   <li>{@code spring.datasource.url} must match the project form enforced by
 *       {@link MigrateUrlPolicy}: plain host/port/database plus an allow-list of
 *       connection parameters. Host-level attributes and percent-encoded credential
 *       keys are rejected, because Connector/J would let them override the account.</li>
 *   <li>{@code spring.flyway.url}, {@code spring.flyway.user},
 *       {@code spring.flyway.password} and {@code spring.datasource.hikari.jdbc-url}
 *       must be absent. Spring Boot builds a separate Flyway {@code DataSource} as soon
 *       as one of the Flyway properties is present, and a URL carrying connection
 *       properties can authenticate as another account even when its text equals the
 *       main datasource URL. Presence is detected in relaxed form, so camelCase,
 *       kebab-case and environment-variable spellings are all caught.</li>
 *   <li>{@code spring.datasource.type} and Hikari's {@code dataSource}/
 *       {@code dataSourceClassName}/{@code dataSourceProperties} family must be absent:
 *       Hikari would otherwise instantiate that class and connect with its own URL and
 *       properties instead of the guarded datasource.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@Profile("migrate")
class MigrateCredentialGuardConfiguration {

    private static final String URL_PROPERTY = "spring.datasource.url";
    private static final String USERNAME_PROPERTY = "spring.datasource.username";
    private static final String PASSWORD_PROPERTY = "spring.datasource.password";
    private static final String HIKARI_USERNAME_PROPERTY = "spring.datasource.hikari.username";
    private static final String HIKARI_PASSWORD_PROPERTY = "spring.datasource.hikari.password";
    private static final String HIKARI_URL_PROPERTY = "spring.datasource.hikari.jdbc-url";
    private static final String FLYWAY_URL_PROPERTY = "spring.flyway.url";
    private static final String FLYWAY_USERNAME_PROPERTY = "spring.flyway.user";
    private static final String FLYWAY_PASSWORD_PROPERTY = "spring.flyway.password";
    private static final String DATASOURCE_TYPE_PROPERTY = "spring.datasource.type";
    private static final String HIKARI_DATA_SOURCE_PROPERTY = "spring.datasource.hikari.data-source";
    private static final String USERNAME_VARIABLE = "DB_MIGRATION_USER";
    private static final String PASSWORD_VARIABLE = "DB_MIGRATION_PASSWORD";

    @Bean
    static BeanFactoryPostProcessor requireMigrationCredentials(Environment environment) {
        return beanFactory -> {
            String username = requireVariable(environment, USERNAME_VARIABLE);
            String password = requireVariable(environment, PASSWORD_VARIABLE);

            requireMatchesVariable(environment, USERNAME_PROPERTY, USERNAME_VARIABLE, username);
            requireMatchesVariable(environment, PASSWORD_PROPERTY, PASSWORD_VARIABLE, password);

            // Hikari binds spring.datasource.hikari.* after the standard properties, so
            // these can override the account that actually connects.
            requireAbsentOrMatchesVariable(environment, HIKARI_USERNAME_PROPERTY, USERNAME_VARIABLE, username);
            requireAbsentOrMatchesVariable(environment, HIKARI_PASSWORD_PROPERTY, PASSWORD_VARIABLE, password);

            MigrateUrlPolicy.requireSupported(environment.getProperty(URL_PROPERTY));

            // A single controlled datasource only: Flyway must not derive its own
            // connection, and Hikari must not be pointed at a second URL or at a
            // replacement DataSource class with its own connection properties.
            requireAbsent(environment, HIKARI_URL_PROPERTY);
            requireAbsent(environment, FLYWAY_URL_PROPERTY);
            requireAbsent(environment, FLYWAY_USERNAME_PROPERTY);
            requireAbsent(environment, FLYWAY_PASSWORD_PROPERTY);
            requireAbsent(environment, DATASOURCE_TYPE_PROPERTY);
            requireAbsentPrefix(environment, HIKARI_DATA_SOURCE_PROPERTY);
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
        if (actual != null && actual.isBlank()) {
            throw blankProperty(property);
        }
        if (actual == null || actual.startsWith("${") || !expected.equals(actual)) {
            throw overridingProperty(property, variable, null);
        }
    }

    /**
     * An optional override is allowed only when it resolves to the guarded value; an
     * explicit blank value is invalid configuration.
     */
    private static void requireAbsentOrMatchesVariable(
            Environment environment, String property, String variable, String expected) {
        String actual;
        try {
            actual = environment.getProperty(property);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            throw overridingProperty(property, variable, unresolvedPlaceholder);
        }
        if (actual == null) {
            return;
        }
        if (actual.isBlank()) {
            throw blankProperty(property);
        }
        if (actual.startsWith("${") || !expected.equals(actual)) {
            throw overridingProperty(property, variable, null);
        }
    }

    /**
     * Rejects a property no matter how it is spelled: YAML/property kebab-case,
     * camelCase in a configuration file, or the environment-variable form. Boot's
     * relaxed binding would accept any of them for Flyway/Hikari.
     */
    private static void requireAbsent(Environment environment, String property) {
        String target = normalise(property);
        requireNoPropertyNamed(environment, property, name -> normalise(name).equals(target));
    }

    /**
     * Rejects a whole property family, e.g. Hikari's {@code data-source},
     * {@code data-source-class-name} and {@code data-source-properties.*}.
     */
    private static void requireAbsentPrefix(Environment environment, String propertyPrefix) {
        String target = normalise(propertyPrefix);
        requireNoPropertyNamed(environment, propertyPrefix, name -> normalise(name).startsWith(target));
    }

    private static void requireNoPropertyNamed(
            Environment environment, String property, Predicate<String> matches) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            throw new IllegalStateException("migrate profile requires a ConfigurableEnvironment to verify "
                    + property + " is absent");
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (matches.test(name)) {
                    throw forbiddenProperty(property);
                }
            }
        }
    }

    private static String normalise(String name) {
        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (character == '.' || character == '-' || character == '_') {
                continue;
            }
            builder.append(Character.toLowerCase(character));
        }
        return builder.toString();
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

    private static IllegalStateException blankProperty(String property) {
        return new IllegalStateException(
                "migrate profile forbids setting " + property + " to a blank value");
    }

    private static IllegalStateException forbiddenProperty(String property) {
        return new IllegalStateException("migrate profile forbids " + property
                + "; the migration must use the single datasource configured by DB_MIGRATION_USER"
                + " and DB_MIGRATION_PASSWORD");
    }
}
