package com.shiptrack.api.repo;

import com.shiptrack.api.support.Utc;
import com.shiptrack.contract.enums.NotificationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class NotificationRepository {

    private static final String COLUMNS = """
            id, order_id, notification_key, run_id, channel, status, retry_count,
            feishu_msg_id, feishu_response_code, last_error, payload, created_at,
            sending_at, next_retry_at, sent_at
            """;

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
        return jdbc.query("SELECT " + COLUMNS + " FROM notifications WHERE notification_key = ?",
                NotificationRecord.MAPPER, notificationKey).stream().findFirst();
    }

    public Optional<NotificationRecord> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM notifications WHERE id = ?",
                NotificationRecord.MAPPER, id).stream().findFirst();
    }

    /** §5.13 list; both filters are optional. Newest first, stable across pages. */
    public List<NotificationRecord> list(Long orderId, NotificationStatus status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM notifications WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (orderId != null) {
            sql.append(" AND order_id = ?");
            args.add(orderId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.wireValue());
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), NotificationRecord.MAPPER, args.toArray());
    }

    public long count(Long orderId, NotificationStatus status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM notifications WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (orderId != null) {
            sql.append(" AND order_id = ?");
            args.add(orderId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.wireValue());
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /**
     * §7.4 atomic claim: only the caller that flips {@code pending → sending}
     * may talk to Feishu. {@code sending_at} becomes this attempt's identifier.
     *
     * <p>The identifier is read back in database form ({@code DATE_FORMAT}) and
     * compared as a string in the write-backs: a JDBC {@link java.sql.Timestamp}
     * parameter would be converted through the driver's connection time zone,
     * which masks the exact column value on every deployment but one.</p>
     *
     * <p>The conditional update and the identifier read share one transaction,
     * so the row lock is held until commit: no other dispatcher (or recovery
     * task) can replace this attempt's {@code sending_at} between the flip and
     * the read. A recovery task that takes over afterwards writes a new token,
     * and the late response is discarded by the conditional write-backs.</p>
     */
    @Transactional
    public Optional<Claimed> claim(long id) {
        int updated = jdbc.update("""
                UPDATE notifications
                SET status = 'sending', sending_at = NOW(3), next_retry_at = NULL
                WHERE id = ? AND status = 'pending'
                """, id);
        if (updated == 0) {
            return Optional.empty();
        }
        NotificationRecord row = findById(id)
                .orElseThrow(() -> new IllegalStateException("claimed notification vanished: " + id));
        String token = attemptToken(id);
        return token == null ? Optional.empty() : Optional.of(new Claimed(row, token));
    }

    /** Oldest claimable notification, or empty. The conditional update decides the winner. */
    public Optional<Long> nextPendingId() {
        return jdbc.queryForList(
                        "SELECT id FROM notifications WHERE status = 'pending' ORDER BY id LIMIT 1",
                        Long.class)
                .stream().findFirst();
    }

    /** Conditional write-back; a late response after recovery affects no row and is discarded. */
    public int markSent(long id, String attemptToken, Integer responseCode, String messageId) {
        return jdbc.update("""
                UPDATE notifications
                SET status = 'sent', sent_at = NOW(3), feishu_response_code = ?,
                    feishu_msg_id = ?, last_error = NULL
                WHERE id = ? AND status = 'sending' AND sending_at = CAST(? AS DATETIME(3))
                """, responseCode, messageId, id, attemptToken);
    }

    /**
     * Marks the attempt failed. {@code retryBackoffMicros} is applied to the
     * database clock ({@code NOW(3)}), so the schedule never depends on the
     * driver's connection time zone; {@code null} means "manual retry only".
     */
    public int markFailed(long id, String attemptToken, String error, Integer responseCode,
                          Long retryBackoffMicros) {
        return jdbc.update("""
                UPDATE notifications
                SET status = 'failed', last_error = ?, feishu_response_code = ?,
                    next_retry_at = DATE_ADD(NOW(3), INTERVAL ? MICROSECOND)
                WHERE id = ? AND status = 'sending' AND sending_at = CAST(? AS DATETIME(3))
                """, truncate(error), responseCode, retryBackoffMicros, id, attemptToken);
    }

    public List<Claimed> findStuckSending(long timeoutMicros) {
        return jdbc.query("SELECT " + COLUMNS
                        + ", DATE_FORMAT(sending_at, '%Y-%m-%d %H:%i:%s.%f') AS attempt_token "
                        + """
                        FROM notifications
                        WHERE status = 'sending' AND sending_at IS NOT NULL
                          AND sending_at < DATE_SUB(NOW(3), INTERVAL ? MICROSECOND)
                        ORDER BY id
                        """,
                (rows, index) -> new Claimed(
                        NotificationRecord.MAPPER.mapRow(rows, index),
                        rows.getString("attempt_token")),
                timeoutMicros);
    }

    private String attemptToken(long id) {
        return jdbc.query("SELECT DATE_FORMAT(sending_at, '%Y-%m-%d %H:%i:%s.%f') "
                        + "FROM notifications WHERE id = ?",
                rows -> rows.next() ? rows.getString(1) : null, id);
    }

    public List<NotificationRecord> findEligibleFailed() {
        return jdbc.query("SELECT " + COLUMNS + """
                        FROM notifications
                        WHERE status = 'failed' AND retry_count < 2
                          AND next_retry_at IS NOT NULL AND next_retry_at <= NOW(3)
                        ORDER BY id
                        """,
                NotificationRecord.MAPPER);
    }

    /** §7.4 automatic retry: {@code failed → pending}, {@code retry_count+1}. */
    public int requeueFailed(long id) {
        return jdbc.update("""
                UPDATE notifications
                SET status = 'pending', retry_count = retry_count + 1, next_retry_at = NULL
                WHERE id = ? AND status = 'failed' AND retry_count < 2
                  AND next_retry_at IS NOT NULL AND next_retry_at <= NOW(3)
                """, id);
    }

    /** §5.14 manual retry: only {@code failed}; no backoff wait. */
    public int retryFailed(long id) {
        return jdbc.update("""
                UPDATE notifications
                SET status = 'pending', retry_count = retry_count + 1, next_retry_at = NULL
                WHERE id = ? AND status = 'failed'
                """, id);
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

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    public record RunNotificationCounts(int created, int sent, int failed) {
    }

    /** A claimed row together with the database form of this attempt's {@code sending_at}. */
    public record Claimed(NotificationRecord notification, String attemptToken) {
    }
}
