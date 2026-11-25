package com.shiptrack.api.repo;

import com.shiptrack.api.support.Utc;
import com.shiptrack.contract.enums.AttemptStatus;
import com.shiptrack.contract.enums.RunTrigger;
import com.shiptrack.contract.enums.TransportStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SnapshotRepository {

    private static final String COLUMNS = """
            id, container_id, run_id, run_trigger, attempt_status, retry_count, queued_at,
            claimed_at, claim_token, started_at, finished_at, source, success, transport_status,
            raw_status, etas, source_events, location, vessel, voyage, pol, pod,
            final_destination, content_hash, mapping_version, error_code, error_message
            """;

    public static final RowMapper<SnapshotRecord> MAPPER = (rows, index) -> new SnapshotRecord(
            rows.getLong("id"),
            rows.getLong("container_id"),
            rows.getString("run_id"),
            RunTrigger.fromWire(rows.getString("run_trigger")),
            AttemptStatus.fromWire(rows.getString("attempt_status")),
            rows.getInt("retry_count"),
            Utc.read(rows, "queued_at"),
            Utc.read(rows, "claimed_at"),
            rows.getString("claim_token"),
            Utc.read(rows, "started_at"),
            Utc.read(rows, "finished_at"),
            rows.getString("source"),
            rows.getObject("success", Boolean.class),
            rows.getString("transport_status") == null
                    ? null : TransportStatus.valueOf(rows.getString("transport_status")),
            rows.getString("raw_status"),
            rows.getString("etas"),
            rows.getString("source_events"),
            rows.getString("location"),
            rows.getString("vessel"),
            rows.getString("voyage"),
            rows.getString("pol"),
            rows.getString("pod"),
            rows.getString("final_destination"),
            rows.getString("content_hash"),
            rows.getObject("mapping_version", Integer.class),
            rows.getString("error_code"),
            rows.getString("error_message"));

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertQueued(long containerId, String runId, RunTrigger trigger, Timestamp queuedAt) {
        return insert(containerId, runId, trigger, "queued", null, queuedAt);
    }

    public long insertSkipped(long containerId, String runId, RunTrigger trigger,
                              String errorCode, Timestamp queuedAt) {
        return insert(containerId, runId, trigger, "skipped", errorCode, queuedAt);
    }

    private long insert(long containerId, String runId, RunTrigger trigger, String status,
                        String errorCode, Timestamp queuedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tracking_snapshots (container_id, run_id, run_trigger,
                        attempt_status, error_code, queued_at, finished_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, containerId);
            statement.setString(2, runId);
            statement.setString(3, trigger.wireValue());
            statement.setString(4, status);
            statement.setString(5, errorCode);
            statement.setTimestamp(6, queuedAt);
            statement.setTimestamp(7, "skipped".equals(status) ? queuedAt : null);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("tracking_snapshots insert returned no id");
        }
        return key.longValue();
    }

    public Optional<SnapshotRecord> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tracking_snapshots WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<SnapshotRecord> findByRunAndContainer(String runId, long containerId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM tracking_snapshots WHERE run_id = ? AND container_id = ?",
                MAPPER, runId, containerId).stream().findFirst();
    }

    public Optional<SnapshotRecord> findByRunAndContainerForUpdate(String runId, long containerId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM tracking_snapshots WHERE run_id = ? AND container_id = ? FOR UPDATE",
                MAPPER, runId, containerId).stream().findFirst();
    }

    public List<SnapshotRecord> findQueued(String runId, int limit) {
        return jdbc.query("SELECT " + COLUMNS
                + " FROM tracking_snapshots WHERE run_id = ? AND attempt_status = 'queued' "
                + "ORDER BY id LIMIT ?", MAPPER, runId, limit);
    }

    public boolean runExists(String runId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tracking_snapshots WHERE run_id = ?", Integer.class, runId);
        return count != null && count > 0;
    }

    public Optional<RunTrigger> findRunTrigger(String runId) {
        return jdbc.query("SELECT run_trigger FROM tracking_snapshots WHERE run_id = ? LIMIT 1",
                (rows, index) -> RunTrigger.fromWire(rows.getString("run_trigger")), runId)
                .stream().findFirst();
    }

    public int markClaimed(long id, String claimToken, Timestamp now) {
        return jdbc.update("""
                UPDATE tracking_snapshots SET attempt_status = 'running', claimed_at = ?,
                    started_at = ?, claim_token = ?
                WHERE id = ? AND attempt_status = 'queued'
                """, now, now, claimToken, id);
    }

    public void markSkipped(long id, String errorCode, Timestamp now) {
        jdbc.update("""
                UPDATE tracking_snapshots SET attempt_status = 'skipped', error_code = ?,
                    finished_at = ?
                WHERE id = ? AND attempt_status = 'queued'
                """, errorCode, now, id);
    }

    public List<Long> findTimedOutContainerIds(String runId, Timestamp before) {
        return jdbc.queryForList("""
                SELECT container_id FROM tracking_snapshots
                WHERE run_id = ? AND attempt_status = 'running' AND claimed_at < ?
                """, Long.class, runId, before);
    }

    /** Timed-out running rows of one run go back to queued (§5.22). */
    public int reclaimTimedOutForRun(String runId, Timestamp before) {
        return jdbc.update("""
                UPDATE tracking_snapshots SET attempt_status = 'queued', claim_token = NULL,
                    claimed_at = NULL, started_at = NULL
                WHERE run_id = ? AND attempt_status = 'running' AND claimed_at < ?
                """, runId, before);
    }

    /** Takes over a specific row held by an older run (§5.22 takeover). */
    public int reclaimRunningRow(long containerId, String oldRunId) {
        return jdbc.update("""
                UPDATE tracking_snapshots SET attempt_status = 'queued', claim_token = NULL,
                    claimed_at = NULL, started_at = NULL
                WHERE container_id = ? AND run_id = ? AND attempt_status = 'running'
                """, containerId, oldRunId);
    }

    public Optional<SnapshotRecord> previousSuccess(long containerId, long excludeSnapshotId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM tracking_snapshots WHERE container_id = ? AND attempt_status = 'success' "
                        + "AND id <> ? ORDER BY finished_at DESC, id DESC LIMIT 1",
                MAPPER, containerId, excludeSnapshotId).stream().findFirst();
    }

    public void completeSuccess(long id, Timestamp finishedAt, int retryCount, String source,
                                String transportStatus, String rawStatus, String etasJson,
                                String sourceEventsJson, String location, String vessel,
                                String voyage, String pol, String pod, String finalDestination,
                                String contentHash, Integer mappingVersion, String rawPayloadJson) {
        jdbc.update("""
                UPDATE tracking_snapshots SET attempt_status = 'success', success = 1,
                    finished_at = ?, retry_count = ?, source = ?, transport_status = ?,
                    raw_status = ?, etas = ?, source_events = ?, location = ?, vessel = ?,
                    voyage = ?, pol = ?, pod = ?, final_destination = ?, content_hash = ?,
                    mapping_version = ?, raw_payload = ?, error_code = NULL, error_message = NULL
                WHERE id = ?
                """, finishedAt, retryCount, source, transportStatus, rawStatus, etasJson,
                sourceEventsJson, location, vessel, voyage, pol, pod, finalDestination,
                contentHash, mappingVersion, rawPayloadJson, id);
    }

    public void completeFailure(long id, Timestamp finishedAt, int retryCount, String source,
                                String errorCode, String errorMessage, Integer mappingVersion,
                                String rawPayloadJson) {
        jdbc.update("""
                UPDATE tracking_snapshots SET attempt_status = 'failed', success = 0,
                    finished_at = ?, retry_count = ?, source = ?, error_code = ?,
                    error_message = ?, mapping_version = ?, raw_payload = ?,
                    transport_status = NULL, etas = NULL, source_events = NULL
                WHERE id = ?
                """, finishedAt, retryCount, source, errorCode, errorMessage, mappingVersion,
                rawPayloadJson, id);
    }

    /** {queued, running, success, failed, skipped} counts plus run timing. */
    public RunCounts runCounts(String runId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query("SELECT attempt_status, COUNT(*) AS n FROM tracking_snapshots "
                        + "WHERE run_id = ? GROUP BY attempt_status",
                resultSet -> {
                    counts.put(resultSet.getString("attempt_status"), resultSet.getInt("n"));
                }, runId);
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tracking_snapshots WHERE run_id = ?", Integer.class, runId);
        OffsetDateTime started = jdbc.query(
                        "SELECT MIN(queued_at) AS ts FROM tracking_snapshots WHERE run_id = ?",
                        (rows, index) -> Utc.read(rows, "ts"), runId)
                .stream().findFirst().orElse(null);
        OffsetDateTime finished = jdbc.query(
                        "SELECT MAX(finished_at) AS ts FROM tracking_snapshots WHERE run_id = ?",
                        (rows, index) -> Utc.read(rows, "ts"), runId)
                .stream().findFirst().orElse(null);
        return new RunCounts(total == null ? 0 : total, counts, started, finished);
    }

    public List<ClaimableRun> findClaimableRuns(int limit) {
        return jdbc.query("""
                SELECT run_id, MAX(run_trigger) AS run_trigger, COUNT(*) AS queued_count,
                       MIN(queued_at) AS oldest_queued_at
                FROM tracking_snapshots
                WHERE attempt_status = 'queued'
                GROUP BY run_id
                ORDER BY MIN(queued_at) ASC
                LIMIT ?
                """, (rows, index) -> new ClaimableRun(
                rows.getString("run_id"),
                RunTrigger.fromWire(rows.getString("run_trigger")),
                rows.getInt("queued_count"),
                Utc.read(rows, "oldest_queued_at")), limit);
    }

    public int countNonTerminal(long orderId, String runId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tracking_snapshots s
                JOIN containers c ON c.id = s.container_id
                WHERE c.order_id = ? AND s.run_id = ?
                  AND s.attempt_status IN ('queued', 'running')
                """, Integer.class, orderId, runId);
        return count == null ? 0 : count;
    }

    public int countFailedSince(OffsetDateTime since) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tracking_snapshots WHERE attempt_status = 'failed' "
                        + "AND finished_at >= ?", Integer.class, Utc.timestamp(since));
        return count == null ? 0 : count;
    }

    public int countAll() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tracking_snapshots", Integer.class);
        return count == null ? 0 : count;
    }

    public int countByStatus(String runId, AttemptStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tracking_snapshots WHERE run_id = ? AND attempt_status = ?",
                Integer.class, runId, status.wireValue());
        return count == null ? 0 : count;
    }

    public record RunCounts(int total, Map<String, Integer> byStatus,
                            OffsetDateTime startedAt, OffsetDateTime finishedAt) {

        public int count(String status) {
            return byStatus.getOrDefault(status, 0);
        }
    }

    public record ClaimableRun(String runId, RunTrigger trigger, int queuedCount,
                               OffsetDateTime oldestQueuedAt) {
    }
}
