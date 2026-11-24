package com.shiptrack.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Default API profile: Flyway stays disabled (the API runs on a DML-only account)
 * and every JDBC session is forced to UTC by Hikari.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApiContextTest {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shiptrack")
            .withUsername("shiptrack")
            .withPassword("shiptrack-test");

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
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Test
    void apiProfileDoesNotEnableFlyway() {
        assertTrue(context.getBeansOfType(Flyway.class).isEmpty());
        assertFalse(Boolean.parseBoolean(
                context.getEnvironment().getProperty("spring.flyway.enabled")));
    }

    @Test
    void apiProfileDoesNotCreateFlywayHistoryTable() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE() "
                             + "AND table_name = 'flyway_schema_history'")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void jdbcSessionTimeZoneIsUtc() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT @@session.time_zone")) {
            assertTrue(rows.next());
            assertEquals("+00:00", rows.getString(1));
        }
    }
}
