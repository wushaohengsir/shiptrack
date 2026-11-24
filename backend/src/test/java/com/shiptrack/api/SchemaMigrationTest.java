package com.shiptrack.api;

import com.shiptrack.contract.enums.TransportStatus;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void allDocumentedIndexesExistWithExactColumnsAndUniqueness() throws SQLException {
        assertIndex("orders", "uk_orders_order_no", true, "order_no");
        assertIndex("orders", "idx_orders_archived_updated", false, "archived_at", "updated_at");

        assertIndex("containers", "uk_containers_order_container", true, "order_id", "container_no");
        assertIndex("containers", "idx_containers_active_order", false, "is_active", "order_id");
        assertIndex("containers", "idx_containers_lock_run", false, "lock_run_id");

        assertIndex("tracking_snapshots", "uk_snapshots_run_container", true, "run_id", "container_id");
        assertIndex("tracking_snapshots", "idx_snapshots_container_finished", false,
                "container_id", "finished_at");
        assertIndex("tracking_snapshots", "idx_snapshots_status_claimed", false,
                "attempt_status", "claimed_at");
        assertIndex("tracking_snapshots", "idx_snapshots_queued", false, "queued_at");

        assertIndex("tracking_events", "uk_events_event_key", true, "event_key");
        assertIndex("tracking_events", "idx_events_container_detected", false,
                "container_id", "detected_at");
        assertIndex("tracking_events", "idx_events_notification", false, "notification_id");

        assertIndex("notifications", "uk_notifications_key", true, "notification_key");
        assertIndex("notifications", "idx_notifications_status_retry", false,
                "status", "next_retry_at");
        assertIndex("notifications", "idx_notifications_order_created", false,
                "order_id", "created_at");
    }

    @Test
    void everyTransportStatusFitsBothStatusColumns() throws SQLException {
        assertEquals(16, TransportStatus.values().length);
        assertEquals(32, columnLength("containers", "transport_status"));
        assertEquals(32, columnLength("tracking_snapshots", "transport_status"));

        try (Connection connection = open()) {
            long orderId = insertReturningId(connection,
                    "INSERT INTO orders (order_no) VALUES ('PO-ALL-STATUSES')");
            long containerId = insertReturningId(connection,
                    "INSERT INTO containers (order_id, container_no) VALUES ("
                            + orderId + ", 'MSCU1234567')");

            for (TransportStatus status : TransportStatus.values()) {
                try (Statement statement = connection.createStatement()) {
                    assertEquals(1, statement.executeUpdate(
                            "UPDATE containers SET transport_status = '" + status.name()
                                    + "' WHERE id = " + containerId));
                }
                insertReturningId(connection,
                        "INSERT INTO tracking_snapshots (container_id, run_id, run_trigger, "
                                + "attempt_status, queued_at, transport_status) VALUES ("
                                + containerId + ", 'run_" + status.name() + "', 'schedule', "
                                + "'success', UTC_TIMESTAMP(3), '" + status.name() + "')");
                assertEquals(status, TransportStatus.valueOf(
                        readString("SELECT transport_status FROM containers WHERE id = " + containerId)));
            }

            long snapshotStatuses = readInt("SELECT COUNT(*) FROM tracking_snapshots "
                    + "WHERE container_id = " + containerId);
            assertEquals(TransportStatus.values().length, snapshotStatuses);
        }
    }

    @Test
    void checksumValidDefaultsToTrueAndRecordsForcedInvalid() throws SQLException {
        try (Connection connection = open()) {
            long orderId = insertReturningId(connection,
                    "INSERT INTO orders (order_no) VALUES ('PO-CHECKSUM')");
            long validContainer = insertReturningId(connection,
                    "INSERT INTO containers (order_id, container_no) VALUES ("
                            + orderId + ", 'MSKU7654321')");
            long forcedContainer = insertReturningId(connection,
                    "INSERT INTO containers (order_id, container_no, checksum_valid) VALUES ("
                            + orderId + ", 'MSKU7654322', 0)");

            assertEquals(1, readInt("SELECT checksum_valid FROM containers WHERE id = " + validContainer));
            assertEquals(0, readInt("SELECT checksum_valid FROM containers WHERE id = " + forcedContainer));
        }
    }

    @Test
    void everySnapshotReferenceIsDeleteRestricted() throws SQLException {
        try (Connection connection = open()) {
            long orderId = insertReturningId(connection,
                    "INSERT INTO orders (order_no) VALUES ('PO-RESTRICT')");
            long containerId = insertReturningId(connection,
                    "INSERT INTO containers (order_id, container_no) VALUES ("
                            + orderId + ", 'CMAU1234567')");

            long baselineSnapshot = insertSnapshot(connection, containerId, "run_baseline");
            long fromSnapshot = insertSnapshot(connection, containerId, "run_from");
            long toSnapshot = insertSnapshot(connection, containerId, "run_to");
            long eventBaselineSnapshot = insertSnapshot(connection, containerId, "run_evt_baseline");

            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "UPDATE containers SET baseline_snapshot_id = " + baselineSnapshot
                                + " WHERE id = " + containerId));
            }
            insertReturningId(connection,
                    "INSERT INTO tracking_events (container_id, event_key, event_type, "
                            + "from_snapshot_id, to_snapshot_id, baseline_snapshot_id, detected_at) "
                            + "VALUES (" + containerId + ", 'evt_all_refs', 'ETA_CHANGED', "
                            + fromSnapshot + ", " + toSnapshot + ", " + eventBaselineSnapshot
                            + ", UTC_TIMESTAMP(3))");

            for (long snapshotId : new long[]{
                    baselineSnapshot, fromSnapshot, toSnapshot, eventBaselineSnapshot}) {
                assertThrows(SQLException.class, () -> execute(
                                "DELETE FROM tracking_snapshots WHERE id = " + snapshotId),
                        () -> "snapshot " + snapshotId + " must be RESTRICT-referenced");
            }
        }
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

    private static void assertIndex(String table, String index, boolean unique, String... columns)
            throws SQLException {
        assertEquals(List.of(columns), indexColumns(table, index),
                () -> "columns of " + table + "." + index);
        assertEquals(unique, indexIsUnique(table, index),
                () -> "uniqueness of " + table + "." + index);
    }

    private static boolean indexIsUnique(String table, String index) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT non_unique FROM information_schema.statistics "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), () -> "index not found: " + table + "." + index);
                return rows.getInt(1) == 0;
            }
        }
    }

    private static long columnLength(String table, String column) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT character_maximum_length FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), () -> "column not found: " + table + "." + column);
                return rows.getLong(1);
            }
        }
    }

    private static long insertSnapshot(Connection connection, long containerId, String runId)
            throws SQLException {
        return insertReturningId(connection,
                "INSERT INTO tracking_snapshots (container_id, run_id, run_trigger, "
                        + "attempt_status, queued_at) VALUES (" + containerId + ", '" + runId
                        + "', 'schedule', 'success', UTC_TIMESTAMP(3))");
    }

    private static int readInt(String sql) throws SQLException {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static String readString(String sql) throws SQLException {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
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
