package com.shiptrack.api;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Applies the real Flyway migration to a MySQL 8.4 container and verifies the
 * five core tables, their indexes and the key constraints.
 */
class SchemaMigrationTest {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shiptrack")
            .withUsername("shiptrack")
            .withPassword("shiptrack-test");

    private static final String RUN_ID = "run_01JBQ8Z6T3K9M2X4V7N1P5R8S0";
    private static final String CLAIM_TOKEN = "clm_01JBQ8Z6T3K9M2X4V7N1P5R8S0";
    private static final String CONTENT_HASH = "sha256:" + "a".repeat(64);

    private static Flyway flyway;

    @BeforeAll
    static void migrate() {
        MYSQL.start();
        flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }

    @AfterAll
    static void stopContainer() {
        MYSQL.stop();
    }

    @Test
    void flywayAppliedV1() {
        MigrationInfo[] applied = flyway.info().applied();
        assertEquals(1, applied.length);
        assertEquals("1", applied[0].getVersion().getVersion());
        assertEquals("V1__init_schema.sql", applied[0].getScript());
    }

    @Test
    void fiveCoreTablesExist() throws SQLException {
        Set<String> expected = Set.of(
                "orders", "containers", "tracking_snapshots", "tracking_events", "notifications");
        Set<String> actual = new HashSet<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.add(rows.getString(1).toLowerCase());
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(actual.containsAll(expected),
                () -> "missing tables, actual=" + actual);
        assertEquals(expected.size(), actual.stream().filter(name -> !name.startsWith("flyway_")).count());
    }

    @Test
    void documentedIndexesExist() throws SQLException {
        assertEquals(List.of("order_id", "container_no"),
                indexColumns("containers", "uk_containers_order_container"));
        assertEquals(List.of("run_id", "container_id"),
                indexColumns("tracking_snapshots", "uk_snapshots_run_container"));
        assertEquals(List.of("event_key"), indexColumns("tracking_events", "uk_events_event_key"));
        assertEquals(List.of("notification_key"), indexColumns("notifications", "uk_notifications_key"));
        assertEquals(List.of("archived_at", "updated_at"),
                indexColumns("orders", "idx_orders_archived_updated"));
    }

    @Test
    void validRowChainCanBeInserted() throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);

            long orderId = insertReturningId(connection,
                    "INSERT INTO orders (order_no, title) VALUES ('PO-001', '宁波→多伦多 11 月批次')");
            long containerId = insertReturningId(connection,
                    "INSERT INTO containers (order_id, container_no, carrier, container_type) "
                            + "VALUES (" + orderId + ", 'MSDU7172233', 'MSC', '40HC')");
            long snapshotId = insertReturningId(connection,
                    "INSERT INTO tracking_snapshots (container_id, run_id, run_trigger, attempt_status, "
                            + "queued_at, claimed_at, claim_token, started_at, finished_at, source, success, "
                            + "transport_status, etas, source_events, content_hash, mapping_version) VALUES ("
                            + containerId + ", '" + RUN_ID + "', 'schedule', 'success', "
                            + "UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), '" + CLAIM_TOKEN + "', "
                            + "UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'msc_web', 1, 'DEPARTED', "
                            + "'[]', '[]', '" + CONTENT_HASH + "', 3)");
            long notificationId = insertReturningId(connection,
                    "INSERT INTO notifications (order_id, notification_key, run_id, channel, status, payload) "
                            + "VALUES (" + orderId + ", 'order:" + orderId + ":run:" + RUN_ID
                            + ":channel:feishu', '" + RUN_ID + "', 'feishu', 'pending', '{}')");
            insertReturningId(connection,
                    "INSERT INTO tracking_events (container_id, notification_id, event_key, event_type, "
                            + "field_name, event_time_type, from_snapshot_id, to_snapshot_id, detected_at) "
                            + "VALUES (" + containerId + ", " + notificationId + ", 'evt_1', "
                            + "'ETA_CHANGED', 'eta', 'planned', " + snapshotId + ", " + snapshotId
                            + ", UTC_TIMESTAMP(3))");

            // 循环外键：容器当前基线快照在快照写入后回填。
            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "UPDATE containers SET baseline_snapshot_id = " + snapshotId
                                + ", last_success_at = UTC_TIMESTAMP(3) WHERE id = " + containerId));
            }

            connection.commit();
        }
    }

    @Test
    void invalidEnumValueIsRejected() throws SQLException {
        try (Connection connection = open()) {
            long orderId = insertReturningId(connection,
                    "INSERT INTO orders (order_no) VALUES ('PO-BAD-STATUS')");
            long containerId = insertReturningId(connection,
                    "INSERT INTO containers (order_id, container_no) VALUES ("
                            + orderId + ", 'OOLU1234567')");
            assertThrows(SQLException.class, () -> execute(
                    "UPDATE containers SET transport_status = 'BOGUS' WHERE id = " + containerId));
            assertThrows(SQLException.class, () -> execute(
                    "UPDATE containers SET tracked_milestone = 'PORT' WHERE id = " + containerId));
        }
    }

    @Test
    void containerNumberFormatIsEnforced() throws SQLException {
        try (Connection connection = open()) {
            long orderId = insertReturningId(connection,
                    "INSERT INTO orders (order_no) VALUES ('PO-002')");
            assertThrows(SQLException.class, () -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO containers (order_id, container_no) VALUES ("
                            + orderId + ", 'MSDU717223')");
                }
            });
            assertThrows(SQLException.class, () -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO containers (order_id, container_no) VALUES ("
                            + orderId + ", 'msdu7172233')");
                }
            });
        }
    }

    @Test
    void duplicateRunContainerAttemptIsRejected() throws SQLException {
        try (Connection connection = open()) {
            long orderId = insertReturningId(connection,
                    "INSERT INTO orders (order_no) VALUES ('PO-003')");
            long containerId = insertReturningId(connection,
                    "INSERT INTO containers (order_id, container_no) VALUES ("
                            + orderId + ", 'MSKU1234567')");

            String insert = "INSERT INTO tracking_snapshots (container_id, run_id, run_trigger, "
                    + "attempt_status, queued_at) VALUES (" + containerId + ", '" + RUN_ID
                    + "', 'schedule', 'queued', UTC_TIMESTAMP(3))";
            execute(insert);
            assertThrows(SQLException.class, () -> execute(insert));
        }
    }

    @Test
    void referencedSnapshotCannotBeDeleted() throws SQLException {
        try (Connection connection = open()) {
            long orderId = insertReturningId(connection,
                    "INSERT INTO orders (order_no) VALUES ('PO-004')");
            long containerId = insertReturningId(connection,
                    "INSERT INTO containers (order_id, container_no) VALUES ("
                            + orderId + ", 'TGHU9876543')");
            long snapshotId = insertReturningId(connection,
                    "INSERT INTO tracking_snapshots (container_id, run_id, run_trigger, "
                            + "attempt_status, queued_at) VALUES (" + containerId + ", '" + RUN_ID
                            + "', 'schedule', 'success', UTC_TIMESTAMP(3))");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE containers SET baseline_snapshot_id = " + snapshotId
                        + " WHERE id = " + containerId);
            }
            assertThrows(SQLException.class, () -> execute(
                    "DELETE FROM tracking_snapshots WHERE id = " + snapshotId));
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = open();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static long insertReturningId(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static List<String> indexColumns(String table, String index) throws SQLException {
        java.util.List<String> columns = new java.util.ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT column_name FROM information_schema.statistics "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? "
                             + "ORDER BY seq_in_index")) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1).toLowerCase());
                }
            }
        }
        return columns;
    }
}
