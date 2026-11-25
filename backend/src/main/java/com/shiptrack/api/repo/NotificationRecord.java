package com.shiptrack.api.repo;

import com.shiptrack.api.support.Utc;
import com.shiptrack.contract.enums.NotificationStatus;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;

public record NotificationRecord(
        long id,
        long orderId,
        String notificationKey,
        String runId,
        String channel,
        NotificationStatus status,
        int retryCount,
        String payloadJson,
        OffsetDateTime createdAt,
        OffsetDateTime sendingAt,
        OffsetDateTime nextRetryAt,
        OffsetDateTime sentAt
) {
    public static final RowMapper<NotificationRecord> MAPPER = (rows, index) -> new NotificationRecord(
            rows.getLong("id"),
            rows.getLong("order_id"),
            rows.getString("notification_key"),
            rows.getString("run_id"),
            rows.getString("channel"),
            NotificationStatus.fromWire(rows.getString("status")),
            rows.getInt("retry_count"),
            rows.getString("payload"),
            Utc.read(rows, "created_at"),
            Utc.read(rows, "sending_at"),
            Utc.read(rows, "next_retry_at"),
            Utc.read(rows, "sent_at"));
}
