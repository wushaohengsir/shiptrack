package com.shiptrack.contract.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.NotificationStatus;
import com.shiptrack.contract.enums.OrderNotificationState;

/**
 * Order notification outcome of a result report.
 * {@code notificationId}/{@code status} are null when the state is not {@code created}.
 *
 * <p>Source: 接口文档_终稿.md §5.23</p>
 */
public record OrderNotificationResult(
        @JsonProperty("state") OrderNotificationState state,
        @JsonProperty("notification_id") Long notificationId,
        @JsonProperty("status") NotificationStatus status
) {
}
