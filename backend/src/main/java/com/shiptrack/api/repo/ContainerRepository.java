package com.shiptrack.api.repo;

import com.shiptrack.api.support.Utc;
import com.shiptrack.contract.enums.EtaPrecision;
import com.shiptrack.contract.enums.EtaRisk;
import com.shiptrack.contract.enums.TrackedMilestone;
import com.shiptrack.contract.enums.TrackingHealth;
import com.shiptrack.contract.enums.TransportStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class ContainerRepository {

    private static final String COLUMNS = """
            id, order_id, container_no, carrier, container_type, checksum_valid,
            tracked_milestone, baseline_snapshot_id, transport_status, eta_risk, tracking_health,
            tracked_eta_raw_value, tracked_eta_date, tracked_eta_time, tracked_eta_timezone,
            tracked_eta_precision, tracked_eta_utc_at, pol, pod, final_destination, location,
            vessel, voyage, continue_after_pickup, tracking_started_at, last_attempt_at,
            last_success_at, is_active, error_code, lock_run_id, lock_acquired_at,
            created_at, updated_at
            """;

    public static final RowMapper<ContainerRecord> MAPPER = (rows, index) -> new ContainerRecord(
            rows.getLong("id"),
            rows.getLong("order_id"),
            rows.getString("container_no"),
            rows.getString("carrier"),
            rows.getString("container_type"),
            rows.getBoolean("checksum_valid"),
            TrackedMilestone.valueOf(rows.getString("tracked_milestone")),
            rows.getObject("baseline_snapshot_id", Long.class),
            TransportStatus.valueOf(rows.getString("transport_status")),
            EtaRisk.valueOf(rows.getString("eta_risk")),
            TrackingHealth.valueOf(rows.getString("tracking_health")),
            rows.getString("tracked_eta_raw_value"),
            rows.getObject("tracked_eta_date", java.time.LocalDate.class),
            rows.getObject("tracked_eta_time", java.time.LocalTime.class),
            rows.getString("tracked_eta_timezone"),
            rows.getString("tracked_eta_precision") == null
                    ? null : EtaPrecision.valueOf(rows.getString("tracked_eta_precision")),
            Utc.read(rows, "tracked_eta_utc_at"),
            rows.getString("pol"),
            rows.getString("pod"),
            rows.getString("final_destination"),
            rows.getString("location"),
            rows.getString("vessel"),
            rows.getString("voyage"),
            rows.getBoolean("continue_after_pickup"),
            Utc.read(rows, "tracking_started_at"),
            Utc.read(rows, "last_attempt_at"),
            Utc.read(rows, "last_success_at"),
            rows.getBoolean("is_active"),
            rows.getString("error_code"),
            rows.getString("lock_run_id"),
            Utc.read(rows, "lock_acquired_at"),
            Utc.read(rows, "created_at"),
            Utc.read(rows, "updated_at"));

    private final JdbcTemplate jdbc;

    public ContainerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long orderId, String containerNo, String carrier, String containerType,
                       TrackedMilestone milestone, boolean continueAfterPickup,
                       boolean checksumValid) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Timestamp now = Utc.now();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO containers (order_id, container_no, carrier, container_type,
                        checksum_valid, tracked_milestone, continue_after_pickup,
                        tracking_started_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, orderId);
            statement.setString(2, containerNo);
            statement.setString(3, carrier);
            statement.setString(4, containerType);
            statement.setBoolean(5, checksumValid);
            statement.setString(6, milestone.name());
            statement.setBoolean(7, continueAfterPickup);
            statement.setTimestamp(8, now);
            statement.setTimestamp(9, now);
            statement.setTimestamp(10, now);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("containers insert returned no id");
        }
        return key.longValue();
    }

    public Optional<ContainerRecord> findById(long id) {
        List<ContainerRecord> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM containers WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** Row lock for claim/report (§5.22/5.23 lock order: container first). */
    public Optional<ContainerRecord> findByIdForUpdate(long id) {
        List<ContainerRecord> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM containers WHERE id = ? FOR UPDATE", MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<ContainerRecord> findByOrderId(long orderId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM containers WHERE order_id = ? ORDER BY id",
                MAPPER, orderId);
    }

    public List<ContainerRecord> findActiveByOrderId(long orderId) {
        return jdbc.query("SELECT " + COLUMNS
                + " FROM containers WHERE order_id = ? AND is_active = 1 ORDER BY id", MAPPER, orderId);
    }

    public List<Long> findAllActiveIds() {
        return jdbc.queryForList("""
                SELECT c.id FROM containers c
                JOIN orders o ON o.id = c.order_id
                WHERE c.is_active = 1 AND o.archived_at IS NULL
                ORDER BY c.id
                """, Long.class);
    }

    public boolean existsByOrderAndNumber(long orderId, String containerNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM containers WHERE order_id = ? AND container_no = ?",
                Integer.class, orderId, containerNo);
        return count != null && count > 0;
    }

    public void updateSettings(long id, TrackedMilestone milestone, String containerType,
                               boolean continueAfterPickup, boolean active,
                               boolean resetBaseline, boolean reactivated) {
        if (resetBaseline) {
            jdbc.update("""
                    UPDATE containers SET tracked_milestone = ?, container_type = ?,
                        continue_after_pickup = ?, is_active = ?,
                        baseline_snapshot_id = NULL,
                        tracked_eta_raw_value = NULL, tracked_eta_date = NULL,
                        tracked_eta_time = NULL, tracked_eta_timezone = NULL,
                        tracked_eta_precision = NULL, tracked_eta_utc_at = NULL,
                        eta_risk = 'NO_ETA', updated_at = ?
                    WHERE id = ?
                    """, milestone.name(), containerType, continueAfterPickup, active,
                    Utc.now(), id);
        } else if (reactivated) {
            jdbc.update("""
                    UPDATE containers SET tracked_milestone = ?, container_type = ?,
                        continue_after_pickup = ?, is_active = ?,
                        tracking_started_at = ?, tracking_health = 'OK', updated_at = ?
                    WHERE id = ?
                    """, milestone.name(), containerType, continueAfterPickup, active,
                    Utc.now(), Utc.now(), id);
        } else {
            jdbc.update("""
                    UPDATE containers SET tracked_milestone = ?, container_type = ?,
                        continue_after_pickup = ?, is_active = ?, updated_at = ?
                    WHERE id = ?
                    """, milestone.name(), containerType, continueAfterPickup, active,
                    Utc.now(), id);
        }
    }

    public void deactivate(long id) {
        jdbc.update("UPDATE containers SET is_active = 0, updated_at = ? WHERE id = ?",
                Utc.now(), id);
    }

    public void acquireLock(long id, String runId) {
        jdbc.update("UPDATE containers SET lock_run_id = ?, lock_acquired_at = ?, updated_at = ? "
                + "WHERE id = ?", runId, Utc.now(), Utc.now(), id);
    }

    public void releaseLock(long id, String runId) {
        jdbc.update("UPDATE containers SET lock_run_id = NULL, lock_acquired_at = NULL, updated_at = ? "
                + "WHERE id = ? AND lock_run_id = ?", Utc.now(), id, runId);
    }

    public void releaseLocksOwnedBy(String runId) {
        jdbc.update("UPDATE containers SET lock_run_id = NULL, lock_acquired_at = NULL, updated_at = ? "
                + "WHERE lock_run_id = ?", Utc.now(), runId);
    }

    /**
     * Writes the successful-query state. {@code baselineSnapshotId} is only
     * applied when the container has no baseline yet; an existing baseline is
     * kept (cumulative delta source).
     */
    public void updateAfterSuccess(long id, OffsetDateTime now, String transportStatus,
                                   EtaRisk etaRisk, String rawEta, java.time.LocalDate etaDate,
                                   java.time.LocalTime etaTime, String etaTimezone,
                                   EtaPrecision etaPrecision, OffsetDateTime etaUtcAt,
                                   String pol, String pod, String finalDestination,
                                   String location, String vessel, String voyage,
                                   Long baselineSnapshotId, OffsetDateTime trackingStartedAt) {
        jdbc.update("""
                UPDATE containers SET transport_status = ?, eta_risk = ?, tracking_health = 'OK',
                    error_code = NULL,
                    tracked_eta_raw_value = ?, tracked_eta_date = ?, tracked_eta_time = ?,
                    tracked_eta_timezone = ?, tracked_eta_precision = ?, tracked_eta_utc_at = ?,
                    pol = COALESCE(?, pol), pod = COALESCE(?, pod),
                    final_destination = COALESCE(?, final_destination),
                    location = COALESCE(?, location), vessel = COALESCE(?, vessel),
                    voyage = COALESCE(?, voyage),
                    baseline_snapshot_id = COALESCE(baseline_snapshot_id, ?),
                    tracking_started_at = COALESCE(tracking_started_at, ?),
                    last_attempt_at = ?, last_success_at = ?, updated_at = ?
                WHERE id = ?
                """,
                transportStatus, etaRisk.name(), rawEta, etaDate, etaTime, etaTimezone,
                etaPrecision == null ? null : etaPrecision.name(),
                Utc.timestamp(etaUtcAt), pol, pod, finalDestination, location, vessel, voyage,
                baselineSnapshotId, Utc.timestamp(trackingStartedAt),
                Utc.timestamp(now), Utc.timestamp(now), Utc.timestamp(now), id);
    }

    public void updateAfterFailure(long id, OffsetDateTime now, String errorCode,
                                   TrackingHealth health) {
        jdbc.update("""
                UPDATE containers SET last_attempt_at = ?, error_code = ?,
                    tracking_health = ?, updated_at = ?
                WHERE id = ?
                """, Utc.timestamp(now), errorCode, health.name(), Utc.timestamp(now), id);
    }

    public void updateEtaRisk(long id, EtaRisk etaRisk) {
        jdbc.update("UPDATE containers SET eta_risk = ?, updated_at = ? WHERE id = ?",
                etaRisk.name(), Utc.now(), id);
    }

    public int countActive() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM containers WHERE is_active = 1", Integer.class);
        return count == null ? 0 : count;
    }

    public OffsetDateTime latestSuccessAt() {
        List<OffsetDateTime> rows = jdbc.query(
                "SELECT MAX(last_success_at) AS ts FROM containers",
                (resultSet, index) -> Utc.read(resultSet, "ts"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Instant latestSuccessInstant() {
        OffsetDateTime value = latestSuccessAt();
        return value == null ? null : value.toInstant();
    }
}
