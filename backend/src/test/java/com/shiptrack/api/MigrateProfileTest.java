package com.shiptrack.api;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `migrate` profile: Flyway is enabled, runs V1 on the dedicated migration account
 * and the context is not a web app.
 *
 * <p>The container only knows the migration account; DB_USER/DB_PASSWORD are set to a
 * decoy that does not exist. A fallback to the runtime account would therefore fail to
 * connect, so a green context proves the migration profile uses DB_MIGRATION_* only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("migrate")
class MigrateProfileTest {

    private static final String MIGRATION_USER = "shiptrack_migration";
    private static final String MIGRATION_PASSWORD = "migration-test";
    private static final String RUNTIME_USER_DECOY = "shiptrack_runtime";

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shiptrack")
            .withUsername(MIGRATION_USER)
            .withPassword(MIGRATION_PASSWORD);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("DB_MIGRATION_USER", () -> MIGRATION_USER);
        registry.add("DB_MIGRATION_PASSWORD", () -> MIGRATION_PASSWORD);
        // Decoys: no such account exists in this database. Used as a tripwire.
        registry.add("DB_USER", () -> RUNTIME_USER_DECOY);
        registry.add("DB_PASSWORD", () -> "wrong-runtime-password");
    }

    @AfterAll
    static void stopContainer() {
        MYSQL.stop();
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Test
    void migrateProfileAppliesV1AndIsNotAWebApplication() throws SQLException {
        assertNotNull(flyway);
        assertEquals("none", environment.getProperty("spring.main.web-application-type"));

        MigrationInfo[] applied = flyway.info().applied();
        assertEquals(1, applied.length);
        assertEquals("V1__init_schema.sql", applied[0].getScript());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE() AND table_name = 'containers'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void migrateProfileUsesMigrationAccountOnly() throws SQLException {
        assertEquals(MIGRATION_USER, environment.getProperty("spring.datasource.username"));
        assertNotEquals(RUNTIME_USER_DECOY, environment.getProperty("spring.datasource.username"));

        // Flyway may hold its own DataSource; assert against the connection it actually
        // uses, so an independent spring.flyway.* override cannot slip through (R-B3-3).
        DataSource flywayDataSource = flyway.getConfiguration().getDataSource();
        assertNotNull(flywayDataSource, "Flyway must run against a concrete DataSource");

        try (Connection connection = flywayDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT CURRENT_USER()")) {
            assertTrue(rows.next());
            String currentUser = rows.getString(1);
            assertTrue(currentUser.startsWith(MIGRATION_USER + "@"),
                    "expected migration account, got " + currentUser);
        }
    }
}
