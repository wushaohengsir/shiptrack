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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `migrate` profile: Flyway is enabled, runs V1 and the context is not a web app.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("migrate")
class MigrateProfileTest {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shiptrack")
            .withUsername("migration")
            .withPassword("migration-test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
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
}
