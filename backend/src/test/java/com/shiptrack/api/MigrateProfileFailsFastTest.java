package com.shiptrack.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The `migrate` profile must fail fast when the dedicated migration credentials are
 * missing or when the datasource credentials are overridden: no fallback to the API
 * runtime account is allowed (技术栈使用说明 §3.7).
 *
 * <p>Command-line arguments are used for the override cases because they sit above
 * YAML, {@code SPRING_DATASOURCE_*} and {@code -Dspring.datasource.*} in precedence
 * order — the strongest form of the bypass reported as R-B3-2.
 *
 * <p>Overrides of {@code spring.datasource.hikari.*} and {@code spring.flyway.*} are
 * covered as well: they can redirect the account or database that really runs the
 * migration (R-B3-3).
 */
class MigrateProfileFailsFastTest {

    private static final String MIGRATION_USER = "shiptrack_migration";
    private static final String MIGRATION_PASSWORD = "migration-test";

    private static final String MISSING_USER_MESSAGE =
            "migrate profile requires DB_MIGRATION_USER; falling back to DB_USER/DB_PASSWORD is not allowed";
    private static final String MISSING_PASSWORD_MESSAGE =
            "migrate profile requires DB_MIGRATION_PASSWORD; falling back to DB_USER/DB_PASSWORD is not allowed";
    private static final String OVERRIDDEN_USER_MESSAGE =
            "migrate profile requires spring.datasource.username to come from DB_MIGRATION_USER;"
                    + " overriding it with another value is not allowed";
    private static final String OVERRIDDEN_PASSWORD_MESSAGE =
            "migrate profile requires spring.datasource.password to come from DB_MIGRATION_PASSWORD;"
                    + " overriding it with another value is not allowed";
    private static final String OVERRIDDEN_HIKARI_USER_MESSAGE =
            "migrate profile requires spring.datasource.hikari.username to come from DB_MIGRATION_USER;"
                    + " overriding it with another value is not allowed";
    private static final String OVERRIDDEN_FLYWAY_USER_MESSAGE =
            "migrate profile requires spring.flyway.user to come from DB_MIGRATION_USER;"
                    + " overriding it with another value is not allowed";
    private static final String OVERRIDDEN_FLYWAY_PASSWORD_MESSAGE =
            "migrate profile requires spring.flyway.password to come from DB_MIGRATION_PASSWORD;"
                    + " overriding it with another value is not allowed";
    private static final String OVERRIDDEN_HIKARI_URL_MESSAGE =
            "migrate profile requires spring.datasource.hikari.jdbc-url to match spring.datasource.url;"
                    + " pointing the migration at another database is not allowed";
    private static final String OVERRIDDEN_FLYWAY_URL_MESSAGE =
            "migrate profile requires spring.flyway.url to match spring.datasource.url;"
                    + " pointing the migration at another database is not allowed";

    @Test
    void missingMigrationUserAbortsStartup() {
        assertGuardRejects(Map.of("DB_MIGRATION_PASSWORD", "secret"), MISSING_USER_MESSAGE);
    }

    @Test
    void missingMigrationPasswordAbortsStartup() {
        assertGuardRejects(Map.of("DB_MIGRATION_USER", MIGRATION_USER), MISSING_PASSWORD_MESSAGE);
    }

    @Test
    void missingMigrationUserAbortsEvenWhenDatasourceIsOverridden() {
        assertGuardRejects(
                Map.of("DB_MIGRATION_PASSWORD", "secret"),
                MISSING_USER_MESSAGE,
                "--spring.datasource.username=override_runtime",
                "--spring.datasource.password=override_runtime");
    }

    @Test
    void missingMigrationPasswordAbortsEvenWhenDatasourceIsOverridden() {
        assertGuardRejects(
                Map.of("DB_MIGRATION_USER", MIGRATION_USER),
                MISSING_PASSWORD_MESSAGE,
                "--spring.datasource.username=override_runtime",
                "--spring.datasource.password=override_runtime");
    }

    @Test
    void overriddenDatasourceUsernameIsRejected() {
        // Pin the password on the command line so a leaked system property cannot
        // change which credential the guard reports first.
        assertGuardRejects(
                Map.of("DB_MIGRATION_USER", MIGRATION_USER, "DB_MIGRATION_PASSWORD", MIGRATION_PASSWORD),
                OVERRIDDEN_USER_MESSAGE,
                "--spring.datasource.username=override_runtime",
                "--spring.datasource.password=" + MIGRATION_PASSWORD);
    }

    @Test
    void overriddenDatasourcePasswordIsRejected() {
        // Pin the username on the command line so a leaked system property cannot
        // change which credential the guard reports first.
        assertGuardRejects(
                Map.of("DB_MIGRATION_USER", MIGRATION_USER, "DB_MIGRATION_PASSWORD", MIGRATION_PASSWORD),
                OVERRIDDEN_PASSWORD_MESSAGE,
                "--spring.datasource.username=" + MIGRATION_USER,
                "--spring.datasource.password=override_runtime");
    }

    @Test
    void overriddenHikariUsernameIsRejected() {
        // Hikari applies spring.datasource.hikari.* after the standard properties, so
        // this is a real account swap even when spring.datasource.username is correct.
        assertGuardRejects(
                Map.of("DB_MIGRATION_USER", MIGRATION_USER, "DB_MIGRATION_PASSWORD", MIGRATION_PASSWORD),
                OVERRIDDEN_HIKARI_USER_MESSAGE,
                "--spring.datasource.hikari.username=override_runtime");
    }

    @Test
    void overriddenHikariUrlIsRejected() {
        assertGuardRejects(
                Map.of("DB_MIGRATION_USER", MIGRATION_USER, "DB_MIGRATION_PASSWORD", MIGRATION_PASSWORD),
                OVERRIDDEN_HIKARI_URL_MESSAGE,
                "--spring.datasource.hikari.jdbc-url=jdbc:mysql://override.example:3306/shiptrack");
    }

    @Test
    void overriddenFlywayUserIsRejected() {
        // spring.flyway.user makes Flyway build its own DataSource, bypassing the
        // guarded Hikari connection (R-B3-3).
        assertGuardRejects(
                Map.of("DB_MIGRATION_USER", MIGRATION_USER, "DB_MIGRATION_PASSWORD", MIGRATION_PASSWORD),
                OVERRIDDEN_FLYWAY_USER_MESSAGE,
                "--spring.flyway.user=shiptrack_runtime");
    }

    @Test
    void overriddenFlywayPasswordIsRejected() {
        assertGuardRejects(
                Map.of("DB_MIGRATION_USER", MIGRATION_USER, "DB_MIGRATION_PASSWORD", MIGRATION_PASSWORD),
                OVERRIDDEN_FLYWAY_PASSWORD_MESSAGE,
                "--spring.flyway.password=runtime-decoy");
    }

    @Test
    void overriddenFlywayUrlIsRejected() {
        assertGuardRejects(
                Map.of("DB_MIGRATION_USER", MIGRATION_USER, "DB_MIGRATION_PASSWORD", MIGRATION_PASSWORD),
                OVERRIDDEN_FLYWAY_URL_MESSAGE,
                "--spring.flyway.url=jdbc:mysql://override.example:3306/shiptrack");
    }

    @Test
    void missingMigrationUserAbortsEvenWhenFlywayIsOverridden() {
        assertGuardRejects(
                Map.of("DB_MIGRATION_PASSWORD", "secret"),
                MISSING_USER_MESSAGE,
                "--spring.flyway.user=override_runtime",
                "--spring.flyway.password=override_runtime");
    }

    @Test
    void matchingFlywayOverridesAreAllowed() {
        // Explicit but identical values are not a bypass; the guard only rejects a
        // different account or target. Flyway is disabled so the context starts
        // without a database call.
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ApiApplication.class)
                .profiles("migrate")
                .web(WebApplicationType.NONE)
                .properties(Map.of(
                        "DB_MIGRATION_USER", MIGRATION_USER,
                        "DB_MIGRATION_PASSWORD", MIGRATION_PASSWORD))
                .run("--spring.flyway.enabled=false",
                        "--spring.flyway.user=" + MIGRATION_USER,
                        "--spring.flyway.password=" + MIGRATION_PASSWORD)) {
            assertNotNull(context.getId());
        }
    }

    private static void assertGuardRejects(
            Map<String, Object> defaultProperties, String expectedMessage, String... args) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ApiApplication.class)
                .profiles("migrate")
                .web(WebApplicationType.NONE)
                .properties(defaultProperties);

        Throwable failure = assertThrows(Throwable.class, () -> builder.run(args),
                () -> "migrate profile must not start; expected guard message: " + expectedMessage);

        IllegalStateException guardFailure = findIllegalState(failure, expectedMessage);
        assertNotNull(guardFailure,
                () -> "guard did not reject startup with the expected message\n" + describe(failure));

        assertFalse(hasFrameFrom(failure, "org.flywaydb"),
                () -> "startup reached Flyway instead of failing in the credential guard\n" + describe(failure));
        assertFalse(hasFrameFrom(failure, "com.mysql"),
                () -> "startup reached the MySQL driver instead of failing in the credential guard\n"
                        + describe(failure));
        assertFalse(mentionsAny(failure, "communications link failure", "access denied", "flywayinitializer"),
                () -> "startup failed at connection/Flyway stage rather than in the credential guard\n"
                        + describe(failure));
    }

    private static IllegalStateException findIllegalState(Throwable failure, String expectedMessage) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof IllegalStateException candidate
                    && expectedMessage.equals(candidate.getMessage())) {
                return candidate;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    private static boolean hasFrameFrom(Throwable failure, String classPrefix) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frame.getClassName().startsWith(classPrefix)) {
                    return true;
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static boolean mentionsAny(Throwable failure, String... needles) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lowered = message.toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (lowered.contains(needle)) {
                    return true;
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static String describe(Throwable failure) {
        StringBuilder builder = new StringBuilder();
        for (Throwable current = failure; current != null && current.getCause() != current;
                current = current.getCause()) {
            builder.append(current.getClass().getName()).append(": ").append(current.getMessage()).append('\n');
        }
        return builder.toString();
    }
}
