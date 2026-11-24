package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.NotificationChannel;
import com.shiptrack.contract.enums.NotificationStatus;

import java.time.OffsetDateTime;

/**
 * Notification object returned by the public API.
 *
 * <p>Source: 接口文档_终稿.md §3.7</p>
 */
public record NotificationDto(
        @JsonProperty("id") long id,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("notification_key") String notificationKey,
        @JsonProperty("run_id") String runId,
        @JsonProperty("channel") NotificationChannel channel,
        @JsonProperty("status") NotificationStatus status,
        @JsonProperty("retry_count") int retryCount,
        @JsonProperty("feishu_msg_id") String feishuMsgId,
        @JsonProperty("feishu_response_code") Integer feishuResponseCode,
        @JsonProperty("payload") NotificationPayload payload,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("sending_at") OffsetDateTime sendingAt,
        @JsonProperty("next_retry_at") OffsetDateTime nextRetryAt,
        @JsonProperty("sent_at") OffsetDateTime sentAt
) {
}
