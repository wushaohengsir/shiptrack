package com.shiptrack.api.repo;

import com.shiptrack.api.support.Utc;
import com.shiptrack.contract.enums.EtaRisk;
import com.shiptrack.contract.enums.EventTimeType;
import com.shiptrack.contract.enums.EventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class EventRepository {

    private static final String COLUMNS = """
            id, container_id, notification_id, event_key, event_type, field_name, event_time_type,
            from_snapshot_id, to_snapshot_id, baseline_snapshot_id, baseline_delta, risk_before,
            risk_after, old_value, new_value, source_event_at, detected_at, source
            """;

    public static final RowMapper<EventRecord> MAPPER = (rows, index) -> new EventRecord(
            rows.getLong("id"),
            rows.getLong("container_id"),
            rows.getObject("notification_id", Long.class),
            rows.getString("event_key"),
            EventType.valueOf(rows.getString("event_type")),
            rows.getString("field_name"),
            EventTimeType.fromWire(rows.getString("event_time_type")),
            rows.getObject("from_snapshot_id", Long.class),
            rows.getObject("to_snapshot_id", Long.class),
            rows.getObject("baseline_snapshot_id", Long.class),
            rows.getString("baseline_delta"),
            rows.getString("risk_before") == null ? null : EtaRisk.valueOf(rows.getString("risk_before")),
            rows.getString("risk_after") == null ? null : EtaRisk.valueOf(rows.getString("risk_after")),
            rows.getString("old_value"),
            rows.getString("new_value"),
            Utc.read(rows, "source_event_at"),
            Utc.read(rows, "detected_at"),
            rows.getString("source"));

    private final JdbcTemplate jdbc;

    public EventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts an event unless {@code event_key} already exists; the existing
     * row is returned in that case so the API is idempotent (§8.4).
     */
    public EventRecord insertOrGet(long containerId, String eventKey, EventType type,
                                   String fieldName, EventTimeType timeType,
                                   Long fromSnapshotId, Long toSnapshotId,
                                   Long baselineSnapshotId, String baselineDeltaJson,
                                   EtaRisk riskBefore, EtaRisk riskAfter,
                                   String oldValueJson, String newValueJson,
                                   OffsetDateTime detectedAt, String source) {
        int inserted = jdbc.update("""
                INSERT IGNORE INTO tracking_events (container_id, event_key, event_type,
                    field_name, event_time_type, from_snapshot_id, to_snapshot_id,
                    baseline_snapshot_id, baseline_delta, risk_before, risk_after, old_value,
                    new_value, detected_at, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, containerId, eventKey, type.name(), fieldName,
                timeType == null ? null : timeType.wireValue(), fromSnapshotId, toSnapshotId,
                baselineSnapshotId, baselineDeltaJson,
                riskBefore == null ? null : riskBefore.name(),
                riskAfter == null ? null : riskAfter.name(), oldValueJson, newValueJson,
                Utc.timestamp(detectedAt), source);
        if (inserted > 0) {
            return findByKey(eventKey).orElseThrow();
        }
        return findByKey(eventKey).orElseThrow();
    }

    public Optional<EventRecord> findByKey(String eventKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tracking_events WHERE event_key = ?",
                MAPPER, eventKey).stream().findFirst();
    }

    public Optional<EventRecord> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tracking_events WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<EventRecord> findByToSnapshot(long snapshotId) {
        return jdbc.query("SELECT " + COLUMNS
                + " FROM tracking_events WHERE to_snapshot_id = ? ORDER BY id", MAPPER, snapshotId);
    }

    /** Events produced inside one run for one order (for aggregation, §8.6). */
    public List<EventRecord> findForOrderRun(long orderId, String runId) {
        return jdbc.query("""
                        SELECT e.id, e.container_id, e.notification_id, e.event_key,
                            e.event_type, e.field_name, e.event_time_type, e.from_snapshot_id,
                            e.to_snapshot_id, e.baseline_snapshot_id, e.baseline_delta,
                            e.risk_before, e.risk_after, e.old_value, e.new_value,
                            e.source_event_at, e.detected_at, e.source
                        FROM tracking_events e
                        JOIN containers c ON c.id = e.container_id
                        WHERE c.order_id = ? AND e.to_snapshot_id IN (
                            SELECT id FROM tracking_snapshots WHERE run_id = ?)
                        ORDER BY e.id
                        """,
                MAPPER, orderId, runId);
    }

    public void linkNotification(List<Long> eventIds, long notificationId) {
        if (eventIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", eventIds.stream().map(id -> "?").toList());
        Object[] args = new Object[eventIds.size() + 1];
        args[0] = notificationId;
        for (int i = 0; i < eventIds.size(); i++) {
            args[i + 1] = eventIds.get(i);
        }
        jdbc.update("UPDATE tracking_events SET notification_id = ? WHERE id IN ("
                + placeholders + ")", args);
    }

    public int countAll() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tracking_events", Integer.class);
        return count == null ? 0 : count;
    }

    public Timestamp timestamp(OffsetDateTime value) {
        return Utc.timestamp(value);
    }
}
