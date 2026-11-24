package com.shiptrack.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The `migrate` profile must fail fast when the dedicated migration credentials are
 * missing or overridden: no fallback to the API runtime account is allowed
 * (技术栈使用说明 §3.7). No test here connects to a database — every rejection case
 * aborts in the {@link MigrateCredentialGuardConfiguration} BeanFactoryPostProcessor,
 * before a datasource or Flyway bean is created.
 *
 * <p>Command-line arguments are used for the override cases because they sit above
 * YAML, {@code SPRING_DATASOURCE_*} and {@code -Dspring.datasource.*} in precedence
 * order — the strongest form of the bypass reported as R-B3-2.
 *
 * <p>R-B3-3: Flyway and Hikari must not be able to build a second connection. Presence
 * of {@code spring.flyway.url/user/password} or {@code spring.datasource.hikari.jdbc-url}
 * is rejected even when the value matches the guarded one, including relaxed spellings,
 * and a {@code spring.datasource.url} carrying credentials is rejected outright.
 * Hikari's replacement {@code DataSource} family ({@code spring.datasource.type},
 * {@code data-source}, {@code data-source-class-name}, {@code data-source-properties.*})
 * is rejected too, and the URL must match the project form: host/port/database plus
 * approved parameters, so host-level attributes and percent-encoded credential keys
 * cannot reach the driver. Hikari's global {@code hikaricp.configurationFile} system
 * property is rejected as well, since its no-arg configuration constructor would load
 * that file before any guarded setter runs.
 */
class MigrateProfileFailsFastTest {

    private static final String MIGRATION_USER = "shiptrack_migration";
    private static final String MIGRATION_PASSWORD = "migration-test";
    private static final String DATABASE_URL = "jdbc:mysql://db.example:3306/shiptrack";

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
    private static final String BLANK_USER_MESSAGE =
            "migrate profile forbids setting spring.datasource.username to a blank value";
    private static final String BLANK_PASSWORD_MESSAGE =
            "migrate profile forbids setting spring.datasource.password to a blank value";
    private static final String BLANK_HIKARI_USER_MESSAGE =
            "migrate profile forbids setting spring.datasource.hikari.username to a blank value";
    private static final String OVERRIDDEN_HIKARI_USER_MESSAGE =
            "migrate profile requires spring.datasource.hikari.username to come from DB_MIGRATION_USER;"
                    + " overriding it with another value is not allowed";
    private static final String FORBIDDEN_HIKARI_URL_MESSAGE =
            "migrate profile forbids spring.datasource.hikari.jdbc-url;"
                    + " the migration must use the single datasource configured by DB_MIGRATION_USER"
                    + " and DB_MIGRATION_PASSWORD";
    private static final String FORBIDDEN_FLYWAY_URL_MESSAGE =
            "migrate profile forbids spring.flyway.url;"
                    + " the migration must use the single datasource configured by DB_MIGRATION_USER"
                    + " and DB_MIGRATION_PASSWORD";
    private static final String FORBIDDEN_FLYWAY_USER_MESSAGE =
            "migrate profile forbids spring.flyway.user;"
                    + " the migration must use the single datasource configured by DB_MIGRATION_USER"
                    + " and DB_MIGRATION_PASSWORD";
    private static final String FORBIDDEN_FLYWAY_PASSWORD_MESSAGE =
            "migrate profile forbids spring.flyway.password;"
                    + " the migration must use the single datasource configured by DB_MIGRATION_USER"
                    + " and DB_MIGRATION_PASSWORD";
    private static final String EMBEDDED_CREDENTIALS_MESSAGE =
            "migrate profile forbids credentials embedded in spring.datasource.url;"
                    + " use DB_MIGRATION_USER and DB_MIGRATION_PASSWORD instead";
    private static final String HOST_LEVEL_ATTRIBUTES_MESSAGE =
            "migrate profile forbids host-level attributes in spring.datasource.url;"
                    + " only <host>[:<port>]/<database> is allowed";
    private static final String UNSUPPORTED_URL_MESSAGE =
            "migrate profile requires spring.datasource.url to be"
                    + " jdbc:mysql://<host>[:<port>]/<database> with only approved connection parameters";
    private static final String FORBIDDEN_DATASOURCE_TYPE_MESSAGE =
            "migrate profile forbids spring.datasource.type;"
                    + " the migration must use the single datasource configured by DB_MIGRATION_USER"
                    + " and DB_MIGRATION_PASSWORD";
    private static final String FORBIDDEN_HIKARI_DATA_SOURCE_MESSAGE =
            "migrate profile forbids spring.datasource.hikari.data-source;"
                    + " the migration must use the single datasource configured by DB_MIGRATION_USER"
                    + " and DB_MIGRATION_PASSWORD";
    private static final String FORBIDDEN_HIKARI_CONFIGURATION_FILE_MESSAGE =
            "migrate profile forbids hikaricp.configurationFile;"
                    + " the migration must use the single datasource configured by DB_MIGRATION_USER"
                    + " and DB_MIGRATION_PASSWORD";
    private static final String HIKARI_CONFIGURATION_FILE_PROPERTY = "hikaricp.configurationFile";

    @Test
    void missingMigrationUserAbortsStartup() {
        assertGuardRejects(Map.of("DB_MIGRATION_PASSWORD", "secret"), MISSING_USER_MESSAGE);
    }

    @Test
    void missingMigrationPasswordAbortsStartup() {
        assertGuardRejects(Map.of("DB_MIGRATION_USER", MIGRATION_USER), MISSING_PASSWORD_MESSAGE);
    }

    @Test
    void blankMigrationUserAbortsStartup() {
        assertGuardRejects(
                Map.of("DB_MIGRATION_PASSWORD", "secret"),
                MISSING_USER_MESSAGE,
                "--DB_MIGRATION_USER=");
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
                completeCredentials(),
                OVERRIDDEN_USER_MESSAGE,
                "--spring.datasource.username=override_runtime",
                "--spring.datasource.password=" + MIGRATION_PASSWORD);
    }

    @Test
    void overriddenDatasourcePasswordIsRejected() {
        // Pin the username on the command line so a leaked system property cannot
        // change which credential the guard reports first.
        assertGuardRejects(
                completeCredentials(),
                OVERRIDDEN_PASSWORD_MESSAGE,
                "--spring.datasource.username=" + MIGRATION_USER,
                "--spring.datasource.password=override_runtime");
    }

    @Test
    void blankDatasourceUsernameIsRejected() {
        assertGuardRejects(completeCredentials(), BLANK_USER_MESSAGE, "--spring.datasource.username=");
    }

    @Test
    void blankDatasourcePasswordIsRejected() {
        assertGuardRejects(
                completeCredentials(),
                BLANK_PASSWORD_MESSAGE,
                "--spring.datasource.username=" + MIGRATION_USER,
                "--spring.datasource.password=");
    }

    @Test
    void overriddenHikariUsernameIsRejected() {
        // Hikari applies spring.datasource.hikari.* after the standard properties, so
        // this is a real account swap even when spring.datasource.username is correct.
        assertGuardRejects(
                completeCredentials(),
                OVERRIDDEN_HIKARI_USER_MESSAGE,
                "--spring.datasource.hikari.username=override_runtime");
    }

    @Test
    void blankHikariUsernameIsRejected() {
        assertGuardRejects(
                completeCredentials(),
                BLANK_HIKARI_USER_MESSAGE,
                "--spring.datasource.hikari.username=");
    }

    @Test
    void hikariUrlIsForbiddenEvenWhenItMatchesTheDatasourceUrl() {
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_HIKARI_URL_MESSAGE,
                "--spring.datasource.url=" + DATABASE_URL,
                "--spring.datasource.hikari.jdbc-url=" + DATABASE_URL);
    }

    @Test
    void relaxedHikariUrlSpellingIsForbidden() {
        // Boot's relaxed binding accepts camelCase for Hikari's jdbcUrl, so the guard
        // must see it too.
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_HIKARI_URL_MESSAGE,
                "--spring.datasource.hikari.jdbcUrl=" + DATABASE_URL);
    }

    @Test
    void flywayUserIsForbidden() {
        // spring.flyway.user makes Flyway build its own DataSource, bypassing the
        // guarded Hikari connection (R-B3-3).
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_FLYWAY_USER_MESSAGE,
                "--spring.flyway.user=shiptrack_runtime");
    }

    @Test
    void flywayUserIsForbiddenEvenWhenItMatchesTheMigrationVariable() {
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_FLYWAY_USER_MESSAGE,
                "--spring.flyway.user=" + MIGRATION_USER);
    }

    @Test
    void flywayPasswordIsForbidden() {
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_FLYWAY_PASSWORD_MESSAGE,
                "--spring.flyway.password=runtime-decoy");
    }

    @Test
    void flywayPasswordIsForbiddenEvenWhenItMatchesTheMigrationVariable() {
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_FLYWAY_PASSWORD_MESSAGE,
                "--spring.flyway.password=" + MIGRATION_PASSWORD);
    }

    @Test
    void flywayUrlIsForbidden() {
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_FLYWAY_URL_MESSAGE,
                "--spring.flyway.url=jdbc:mysql://override.example:3306/shiptrack");
    }

    @Test
    void flywayUrlIsForbiddenEvenWhenItMatchesTheDatasourceUrl() {
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_FLYWAY_URL_MESSAGE,
                "--spring.datasource.url=" + DATABASE_URL,
                "--spring.flyway.url=" + DATABASE_URL);
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
    void datasourceUrlWithEmbeddedUserParameterIsRejected() {
        assertGuardRejects(
                completeCredentials(),
                EMBEDDED_CREDENTIALS_MESSAGE,
                "--spring.datasource.url=jdbc:mysql://db.example:3306/shiptrack?user=shiptrack_runtime");
    }

    @Test
    void datasourceUrlWithEmbeddedPasswordParameterIsRejected() {
        assertGuardRejects(
                completeCredentials(),
                EMBEDDED_CREDENTIALS_MESSAGE,
                "--spring.datasource.url=jdbc:mysql://db.example:3306/shiptrack?useSSL=false&PASSWORD=secret");
    }

    @Test
    void datasourceUrlWithUserInfoIsRejected() {
        assertGuardRejects(
                completeCredentials(),
                EMBEDDED_CREDENTIALS_MESSAGE,
                "--spring.datasource.url=jdbc:mysql://shiptrack_runtime:secret@db.example:3306/shiptrack");
    }

    @Test
    void datasourceUrlWithHostLevelCredentialsIsRejected() {
        // Connector/J applies host-level attributes after the global connection
        // properties, so the driver would use this account even though Hikari passes
        // DB_MIGRATION_USER/DB_MIGRATION_PASSWORD (R-B3-3).
        assertGuardRejects(
                completeCredentials(),
                HOST_LEVEL_ATTRIBUTES_MESSAGE,
                "--spring.datasource.url=jdbc:mysql://address=(host=db.example)(port=3306)"
                        + "(user=shiptrack_runtime)(password=secret)/shiptrack");
    }

    @Test
    void datasourceUrlWithPercentEncodedCredentialKeyIsRejected() {
        // %75ser decodes to "user"; a plain-text key scan would miss it.
        assertGuardRejects(
                completeCredentials(),
                UNSUPPORTED_URL_MESSAGE,
                "--spring.datasource.url=jdbc:mysql://db.example:3306/shiptrack?%75ser=shiptrack_runtime");
    }

    @Test
    void datasourceUrlWithUnapprovedParameterIsRejected() {
        assertGuardRejects(
                completeCredentials(),
                UNSUPPORTED_URL_MESSAGE,
                "--spring.datasource.url=jdbc:mysql://db.example:3306/shiptrack?autoReconnect=true");
    }

    @Test
    void datasourceTypeIsForbidden() {
        // A replacement DataSource implementation would not use the guarded connection.
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_DATASOURCE_TYPE_MESSAGE,
                "--spring.datasource.type=com.zaxxer.hikari.HikariDataSource");
    }

    @Test
    void hikariDataSourceClassNameIsForbidden() {
        // Hikari instantiates the named class and connects with its own URL and
        // properties, bypassing the guarded datasource (R-B3-3).
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_HIKARI_DATA_SOURCE_MESSAGE,
                "--spring.datasource.hikari.data-source-class-name=com.mysql.cj.jdbc.MysqlDataSource");
    }

    @Test
    void relaxedHikariDataSourceClassNameSpellingIsForbidden() {
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_HIKARI_DATA_SOURCE_MESSAGE,
                "--spring.datasource.hikari.dataSourceClassName=com.mysql.cj.jdbc.MysqlDataSource");
    }

    @Test
    void hikariDataSourcePropertiesAreForbidden() {
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_HIKARI_DATA_SOURCE_MESSAGE,
                "--spring.datasource.hikari.data-source-properties.url="
                        + "jdbc:mysql://override.example:3306/shiptrack");
    }

    @Test
    void hikariConfigurationFileSystemPropertyIsRejected() {
        // HikariConfig's no-arg constructor loads the file named by this JVM system
        // property, which can set dataSourceClassName, jdbcUrl or credentials directly
        // (R-B3-3 follow-up). The property is set on the running JVM on purpose: the
        // environment property name alone would not prove the real Hikari path is closed.
        String previous = System.getProperty(HIKARI_CONFIGURATION_FILE_PROPERTY);
        System.setProperty(HIKARI_CONFIGURATION_FILE_PROPERTY, "/tmp/hikari-bypass.properties");
        try {
            assertGuardRejects(completeCredentials(), FORBIDDEN_HIKARI_CONFIGURATION_FILE_MESSAGE);
        } finally {
            restoreSystemProperty(HIKARI_CONFIGURATION_FILE_PROPERTY, previous);
        }
    }

    @Test
    void hikariConfigurationFileCommandLinePropertyIsRejected() {
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_HIKARI_CONFIGURATION_FILE_MESSAGE,
                "--hikaricp.configurationFile=/tmp/hikari-bypass.properties");
    }

    @Test
    void relaxedHikariConfigurationFileSpellingIsRejected() {
        // The environment-variable spelling Hikari-adjacent configuration sometimes uses;
        // normalisation makes it match the canonical system property name.
        assertGuardRejects(
                completeCredentials(),
                FORBIDDEN_HIKARI_CONFIGURATION_FILE_MESSAGE,
                "--HIKARICP_CONFIGURATION_FILE=/tmp/hikari-bypass.properties");
    }

    @Test
    void standardDatasourceUrlWithConnectionParametersStarts() {
        // A URL with ordinary driver parameters is legitimate; the guard must not
        // mistake them for credentials. Flyway is disabled so the context starts
        // without a database call.
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ApiApplication.class)
                .profiles("migrate")
                .web(WebApplicationType.NONE)
                .properties(completeCredentials())
                .run("--spring.flyway.enabled=false",
                        "--spring.datasource.url=jdbc:mysql://localhost:3306/shiptrack"
                                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
                                + "&useSSL=false&allowPublicKeyRetrieval=true")) {
            assertNotNull(context.getId());
            assertEquals(MIGRATION_USER,
                    context.getEnvironment().getProperty("spring.datasource.username"));
        }
    }

    private static Map<String, Object> completeCredentials() {
        return Map.of("DB_MIGRATION_USER", MIGRATION_USER,
                "DB_MIGRATION_PASSWORD", MIGRATION_PASSWORD);
    }

    private static void restoreSystemProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
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
