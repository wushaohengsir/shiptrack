package com.shiptrack.api.repo;

import com.shiptrack.api.support.Utc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the pending notification if its {@code notification_key} is new;
     * returns the id either way (idempotent per §1.5).
     */
    public long insertPendingIfAbsent(long orderId, String notificationKey, String runId,
                                      String payloadJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT IGNORE INTO notifications (order_id, notification_key, run_id,
                        channel, status, payload, created_at)
                    VALUES (?, ?, ?, 'feishu', 'pending', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, orderId);
            statement.setString(2, notificationKey);
            statement.setString(3, runId);
            statement.setString(4, payloadJson);
            statement.setTimestamp(5, Utc.now());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null && key.longValue() > 0) {
            return key.longValue();
        }
        return findByKey(notificationKey).map(NotificationRecord::id)
                .orElseThrow(() -> new IllegalStateException("notification not found: " + notificationKey));
    }

    public Optional<NotificationRecord> findByKey(String notificationKey) {
        return jdbc.query("SELECT id, order_id, notification_key, run_id, channel, status, "
                        + "retry_count, payload, created_at, sending_at, next_retry_at, sent_at "
                        + "FROM notifications WHERE notification_key = ?",
                NotificationRecord.MAPPER, notificationKey).stream().findFirst();
    }

    public Optional<NotificationRecord> findById(long id) {
        return jdbc.query("SELECT id, order_id, notification_key, run_id, channel, status, "
                        + "retry_count, payload, created_at, sending_at, next_retry_at, sent_at "
                        + "FROM notifications WHERE id = ?",
                NotificationRecord.MAPPER, id).stream().findFirst();
    }

    public RunNotificationCounts countsForRun(String runId) {
        List<RunNotificationCounts> rows = jdbc.query("""
                        SELECT
                            COUNT(*) AS created,
                            COALESCE(SUM(status = 'sent'), 0) AS sent,
                            COALESCE(SUM(status = 'failed'), 0) AS failed
                        FROM notifications WHERE run_id = ?
                        """,
                (resultSet, index) -> new RunNotificationCounts(
                        resultSet.getInt("created"),
                        resultSet.getInt("sent"),
                        resultSet.getInt("failed")), runId);
        return rows.isEmpty() ? new RunNotificationCounts(0, 0, 0) : rows.get(0);
    }

    public record RunNotificationCounts(int created, int sent, int failed) {
    }
}
